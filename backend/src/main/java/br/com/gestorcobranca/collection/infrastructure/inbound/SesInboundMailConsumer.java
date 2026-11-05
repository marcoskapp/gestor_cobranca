package br.com.gestorcobranca.collection.infrastructure.inbound;

import br.com.gestorcobranca.collection.application.InboundMailProperties;
import br.com.gestorcobranca.collection.application.port.in.InboundMailUseCases;
import br.com.gestorcobranca.collection.application.port.in.InboundMailUseCases.InboundMessage;
import br.com.gestorcobranca.collection.application.port.in.InboundMailUseCases.Outcome;
import br.com.gestorcobranca.collection.application.port.in.InboundMailUseCases.Verdicts;
import br.com.gestorcobranca.shared.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Consumidor das notificações do SES inbound (SES grava o e-mail no bucket de entrada e publica no SNS, que entrega na
 * fila SQS). Leitura longa da fila numa thread própria; cada notificação traz os veredictos (SPF, DKIM, DMARC, vírus,
 * spam), os destinatários do envelope e a chave do e-mail no bucket. O e-mail é lido do S3 e entregue ao caso de uso;
 * a mensagem só sai da fila depois do registro (falha = nova entrega pela fila e, após 5 tentativas, a DLQ com alarme).
 * Várias instâncias consomem em paralelo sem duplicar (visibilidade da fila + índice único pelo id do SES).
 * Métricas {@code integration.calls{provider=sqs|s3,operation=inbound_mail}}.
 */
class SesInboundMailConsumer implements SmartLifecycle, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SesInboundMailConsumer.class);
    private static final String OPERATION = "inbound_mail";
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(2);

    private final SqsClient sqs;
    private final S3Client s3;
    private final InboundMailUseCases inbound;
    private final InboundMailProperties properties;
    private final JsonCodec json;
    private final MeterRegistry registry;
    private volatile boolean running;
    private volatile Thread worker;

    SesInboundMailConsumer(SqsClient sqs, S3Client s3, InboundMailUseCases inbound, InboundMailProperties properties,
                           JsonCodec json, MeterRegistry registry) {
        this.sqs = sqs;
        this.s3 = s3;
        this.inbound = inbound;
        this.properties = properties;
        this.json = json;
        this.registry = registry;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker = Thread.ofPlatform().name("inbound-mail-sqs").daemon().start(this::loop);
        LOG.atInfo().addKeyValue("domain", properties.domain()).log("Leitura das respostas por e-mail (SES) iniciada");
    }

    @Override
    public void stop() {
        running = false;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public void close() {
        stop();
        sqs.close();
        s3.close();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    private void loop() {
        Duration backoff = properties.ses().pollInterval();
        while (running) {
            try {
                List<Message> messages = receive();
                for (Message m : messages) {
                    handle(m);
                }
                backoff = properties.ses().pollInterval();
                if (messages.isEmpty()) {
                    sleep(properties.ses().pollInterval());
                }
            } catch (RuntimeException e) {
                if (!running) {
                    break;
                }
                LOG.atWarn().addKeyValue("retryInMs", backoff.toMillis()).addKeyValue("error", e.getClass().getSimpleName())
                        .log("Falha ao ler a fila de e-mails recebidos; nova tentativa");
                sleep(backoff);
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
        }
    }

    private List<Message> receive() {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "error";
        try {
            List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(properties.ses().queueUrl()).maxNumberOfMessages(10).waitTimeSeconds(20)
                    .visibilityTimeout(300).build()).messages();
            outcome = "success";
            return messages;
        } finally {
            count("sqs", outcome, sample);
        }
    }

    /** Uma notificação: lê o e-mail no S3, registra e só então remove da fila. */
    void handle(Message message) {
        Notification n;
        try {
            n = parse(message.body());
        } catch (RuntimeException e) {
            // notificação que não é do SES (ou corrompida): fica na fila até a DLQ, com alarme — nunca some
            LOG.atError().addKeyValue("sqsMessageId", message.messageId()).addKeyValue("error", e.getClass().getSimpleName())
                    .log("Notificação de e-mail recebido ilegível; mantida na fila");
            return;
        }
        if (!properties.ses().bucket().equals(n.bucket())) {
            LOG.atError().addKeyValue("sqsMessageId", message.messageId())
                    .log("Notificação de e-mail aponta para outro bucket; mantida na fila");
            return;
        }
        byte[] raw = read(n.bucket(), n.objectKey());
        Outcome outcome = inbound.receive(new InboundMessage(n.messageId(), raw, n.recipients(), n.verdicts(),
                n.timestamp(), "s3://" + n.bucket() + "/" + n.objectKey()));
        sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(properties.ses().queueUrl())
                .receiptHandle(message.receiptHandle()).build());
        LOG.atInfo().addKeyValue("sesMessageId", n.messageId()).addKeyValue("outcome", outcome)
                .log("E-mail recebido processado");
    }

    private byte[] read(String bucket, String key) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "error";
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket)
                    .key(key).build());
            outcome = "success";
            return bytes.asByteArray();
        } finally {
            count("s3", outcome, sample);
        }
    }

    /** Envelope SNS ({@code Message}) com a notificação do SES (ação S3). */
    @SuppressWarnings("unchecked")
    Notification parse(String body) {
        Map<String, Object> envelope = json.toMap(body);
        Object inner = envelope.get("Message");
        Map<String, Object> ses = inner instanceof String s ? json.toMap(s) : envelope;
        if (!"Received".equals(ses.get("notificationType"))) {
            throw new IllegalArgumentException("notificationType inesperado");
        }
        Map<String, Object> mail = (Map<String, Object>) ses.get("mail");
        Map<String, Object> receipt = (Map<String, Object>) ses.get("receipt");
        Map<String, Object> action = (Map<String, Object>) receipt.get("action");
        if (!"S3".equals(action.get("type"))) {
            throw new IllegalArgumentException("ação do SES não é S3");
        }
        List<String> recipients = new ArrayList<>();
        Object list = receipt.get("recipients");
        if (list instanceof List<?> l) {
            l.forEach(r -> recipients.add(String.valueOf(r)));
        }
        return new Notification(String.valueOf(mail.get("messageId")), Instant.parse(String.valueOf(mail.get("timestamp"))),
                recipients, new Verdicts(status(receipt, "spfVerdict"), status(receipt, "dkimVerdict"),
                status(receipt, "dmarcVerdict"), status(receipt, "virusVerdict"), status(receipt, "spamVerdict")),
                String.valueOf(action.get("bucketName")), String.valueOf(action.get("objectKey")));
    }

    @SuppressWarnings("unchecked")
    private static String status(Map<String, Object> receipt, String name) {
        Object verdict = receipt.get(name);
        return verdict instanceof Map<?, ?> m ? (String) ((Map<String, Object>) m).get("status") : null;
    }

    private void count(String provider, String outcome, Timer.Sample sample) {
        registry.counter("integration.calls", "provider", provider, "operation", OPERATION, "outcome", outcome).increment();
        sample.stop(registry.timer("integration.calls.duration", "provider", provider, "operation", OPERATION));
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record Notification(String messageId, Instant timestamp, List<String> recipients, Verdicts verdicts, String bucket,
                        String objectKey) {
    }
}
