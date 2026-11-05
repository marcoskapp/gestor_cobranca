package br.com.gestorcobranca.collection.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;

/**
 * Leitura das respostas do credor por e-mail ({@code app.collection.inbound-mail.*}, decisões 820–836).
 *
 * @param mode               {@code disabled} (padrão: respostas só para o responsável), {@code ses} (SES → S3 → SNS →
 *                           SQS) ou {@code local} (pasta de arquivos .eml, para desenvolvimento e sandbox)
 * @param domain             subdomínio de entrada (MX no SES); obrigatório fora de {@code disabled}
 * @param localPart          prefixo do endereço de resposta ({@code <localPart>+<token>@<domain>})
 * @param routeValidity      validade do endereço de resposta depois do prazo pedido ao credor (padrão 180 dias)
 * @param maxMessageBytes    tamanho máximo do e-mail aceito (padrão 25 MB; acima, quarentena)
 * @param maxAttachments     anexos por e-mail (padrão 10; acima, quarentena)
 * @param maxTextChars       caracteres do texto guardado (o original fica inteiro)
 * @param perSenderPerHour   e-mails por remetente e hora antes da quarentena por excesso (padrão 20)
 * @param ses                fila e bucket do SES inbound
 * @param local              pasta do adaptador local
 */
@ConfigurationProperties(prefix = "app.collection.inbound-mail")
public record InboundMailProperties(String mode, String domain, String localPart, Duration routeValidity,
                                    Long maxMessageBytes, Integer maxAttachments, Integer maxTextChars,
                                    Integer perSenderPerHour, Ses ses, Local local) {

    public InboundMailProperties {
        mode = mode == null || mode.isBlank() ? "disabled" : mode.trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("disabled") && !mode.equals("ses") && !mode.equals("local")) {
            throw new IllegalArgumentException("app.collection.inbound-mail.mode deve ser disabled, ses ou local");
        }
        domain = domain == null || domain.isBlank() ? null : domain.trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("disabled") && (domain == null || !domain.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+"))) {
            throw new IllegalArgumentException("app.collection.inbound-mail.domain obrigatório com a leitura ligada");
        }
        localPart = localPart == null || localPart.isBlank() ? "contestacao" : localPart.trim().toLowerCase(Locale.ROOT);
        routeValidity = routeValidity == null ? Duration.ofDays(180) : routeValidity;
        maxMessageBytes = maxMessageBytes == null ? 25L * 1024 * 1024 : maxMessageBytes;
        maxAttachments = maxAttachments == null ? 10 : maxAttachments;
        maxTextChars = maxTextChars == null ? 50_000 : maxTextChars;
        perSenderPerHour = perSenderPerHour == null ? 20 : perSenderPerHour;
        ses = ses == null ? new Ses(null, null, null, null) : ses;
        local = local == null ? new Local(null, null) : local;
        if (mode.equals("ses") && (ses.queueUrl() == null || ses.bucket() == null)) {
            throw new IllegalArgumentException("app.collection.inbound-mail.ses.queue-url e .bucket obrigatórios no modo ses");
        }
        if (mode.equals("local") && local.directory() == null) {
            throw new IllegalArgumentException("app.collection.inbound-mail.local.directory obrigatório no modo local");
        }
    }

    public boolean enabled() {
        return !mode.equals("disabled");
    }

    /**
     * @param queueUrl     fila SQS das notificações (SNS → SQS)
     * @param bucket       bucket do e-mail bruto gravado pelo SES
     * @param region       região do SES inbound (padrão: a do armazenamento)
     * @param pollInterval pausa entre leituras longas da fila
     */
    public record Ses(String queueUrl, String bucket, String region, Duration pollInterval) {

        public Ses {
            queueUrl = queueUrl == null || queueUrl.isBlank() ? null : queueUrl.trim();
            bucket = bucket == null || bucket.isBlank() ? null : bucket.trim();
            region = region == null || region.isBlank() ? null : region.trim();
            pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
        }
    }

    /**
     * @param directory    pasta lida pelo adaptador local ({@code *.eml}; lidos vão para {@code processados/})
     * @param pollInterval intervalo de leitura da pasta
     */
    public record Local(String directory, Duration pollInterval) {

        public Local {
            directory = directory == null || directory.isBlank() ? null : directory.trim();
            pollInterval = pollInterval == null ? Duration.ofSeconds(10) : pollInterval;
        }
    }
}
