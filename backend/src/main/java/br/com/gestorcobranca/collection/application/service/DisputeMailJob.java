package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.CollectionProperties;
import br.com.gestorcobranca.collection.application.port.out.DisputeMailer;
import br.com.gestorcobranca.collection.application.port.out.DisputeMailer.OutgoingMail;
import br.com.gestorcobranca.collection.application.port.out.DisputeMailer.TransientMailException;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeCreditorRequest;
import br.com.gestorcobranca.collection.domain.model.DisputeMail;
import br.com.gestorcobranca.collection.domain.model.DisputeMailPurpose;
import br.com.gestorcobranca.collection.domain.repository.DisputeCreditorRequestRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeMailRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Envio de um e-mail ao credor ({@code collection.dispute_mail}), como ator de sistema no tenant, numa transação:
 * e-mail que não está mais na fila → nada a fazer; aceite do servidor → {@code SENT} (o pedido passa a enviado e
 * registra {@code dispute.creditor_request_sent}); recusa definitiva → {@code FAILED}; falha transitória → exceção
 * (job reagendado; na última tentativa o e-mail é marcado {@code FAILED/RETRIES_EXHAUSTED} em transação própria).
 * Entrega "ao menos uma vez": aceite seguido de falha no commit pode reenviar.
 */
@Component
class DisputeMailJob implements JobHandler<DisputeMailJob.Payload> {

    static final String TYPE = "collection.dispute_mail";
    private static final Logger LOG = LoggerFactory.getLogger(DisputeMailJob.class);

    private final DisputeMailRepository mails;
    private final DisputeCreditorRequestRepository requests;
    private final DisputeRepository disputes;
    private final DisputeMailer mailer;
    private final DisputeMailFailures failures;
    private final DisputeCreditorChannel channel;
    private final BusinessEventLogger businessEvents;
    private final CollectionMetrics metrics;
    private final CollectionProperties properties;
    private final Clock clock;

    DisputeMailJob(DisputeMailRepository mails, DisputeCreditorRequestRepository requests, DisputeRepository disputes,
                   DisputeMailer mailer, DisputeMailFailures failures, DisputeCreditorChannel channel,
                   BusinessEventLogger businessEvents, CollectionMetrics metrics, CollectionProperties properties,
                   Clock clock) {
        this.mails = mails;
        this.requests = requests;
        this.disputes = disputes;
        this.mailer = mailer;
        this.failures = failures;
        this.channel = channel;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    record Payload(UUID mailId) {
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public int maxAttempts() {
        return properties.disputes().mailMaxAttempts();
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        Optional<DisputeMail> found = mails.findById(payload.mailId());
        if (found.isEmpty() || !found.get().isQueued()) {
            return;
        }
        DisputeMail mail = found.get();
        DisputeCreditorRequest request = requests.findById(mail.getRequestId()).orElseThrow();
        Dispute dispute = disputes.findById(request.getDisputeId()).orElseThrow();
        if (mail.getPurpose() != DisputeMailPurpose.REQUEST && !request.isAwaiting()) {
            // respondido ou cancelado depois do agendamento do lembrete/cobrança: não envia
            mail.recordAttempt(context.attempt());
            mail.skip(Instant.now(clock));
            mails.save(mail);
            return;
        }
        Instant started = Instant.now(clock);
        DisputeMailer.Result result;
        try {
            // com a leitura por e-mail ligada, a resposta vai ao endereço próprio do pedido (o responsável está em cópia)
            String replyTo = request.getReplyAddress() != null ? request.getReplyAddress() : request.getReplyTo();
            result = mailer.send(new OutgoingMail(channel.senderName(), request.getTo(), request.getCc(),
                    replyTo, subject(mail.getPurpose(), request), text(mail.getPurpose(), request),
                    mail.getId()));
        } catch (TransientMailException e) {
            metrics.disputeMail(mail.getPurpose().name(), "transient");
            if (context.attempt() >= maxAttempts()) {
                failures.exhausted(mail.getId(), context.attempt());
                metrics.disputeMail(mail.getPurpose().name(), "exhausted");
                metrics.jobFailure(TYPE);
            }
            throw e;
        }
        metrics.disputeMailDuration(Duration.between(started, Instant.now(clock)));
        mail.recordAttempt(context.attempt());
        Instant now = Instant.now(clock);
        if (result.accepted()) {
            mail.markSent(now);
            mails.save(mail);
            metrics.disputeMail(mail.getPurpose().name(), "sent");
            if (mail.getPurpose() == DisputeMailPurpose.REQUEST) {
                request.markSent(now);
                requests.save(request);
                businessEvents.log(DisputeEvents.base(BusinessEventTypes.DISPUTE_CREDITOR_REQUEST_SENT, dispute)
                        .attr("sequence", request.getSequenceNumber())
                        .attr("recipients", request.getTo().size() + request.getCc().size())
                        .attr("titles", request.getTitleIds().size())
                        .attr("evidences", request.getEvidenceIds().size())
                        .attr("requestedDays", java.time.temporal.ChronoUnit.DAYS.between(
                                CollectionViews.today(clock), request.getRequestedDueDate()))
                        .attr("templateVersion", request.getTemplateVersion()));
            }
        } else {
            LOG.atWarn().addKeyValue("mailId", mail.getId()).addKeyValue("errorCode", result.errorCode())
                    .log("Servidor de e-mail recusou o e-mail da contestação");
            mail.markFailed(result.errorCode(), now);
            mails.save(mail);
            metrics.disputeMail(mail.getPurpose().name(), "rejected");
            if (mail.getPurpose() == DisputeMailPurpose.REQUEST) {
                request.markFailed();
                requests.save(request);
                businessEvents.log(DisputeEvents.base(BusinessEventTypes.DISPUTE_CREDITOR_REQUEST_FAILED, dispute)
                        .attr("sequence", request.getSequenceNumber()).attr("errorCode", result.errorCode()));
            }
        }
    }

    static String subject(DisputeMailPurpose purpose, DisputeCreditorRequest r) {
        return switch (purpose) {
            case REQUEST -> r.getSubject();
            case REMINDER -> "Lembrete: " + r.getSubject();
            case OVERDUE -> "Retorno vencido: " + r.getSubject();
        };
    }

    /** Orientação ao credor quando a resposta é lida pela plataforma (anexos aceitos e limites). */
    static final String REPLY_FOOTER = "\n\n--\nResponda a este e-mail para registrar o seu retorno na contestação. "
            + "Anexos aceitos: PDF, PNG ou JPEG. Mantenha este endereço de resposta: ele identifica o pedido.";

    static String text(DisputeMailPurpose purpose, DisputeCreditorRequest r) {
        String body = baseText(purpose, r);
        return r.getReplyAddress() == null ? body : body + REPLY_FOOTER;
    }

    private static String baseText(DisputeMailPurpose purpose, DisputeCreditorRequest r) {
        String due = DisputeTexts.date(r.getRequestedDueDate());
        return switch (purpose) {
            case REQUEST -> r.getBody();
            case REMINDER -> "Lembrete: o prazo para o retorno deste pedido vence em " + due
                    + ". Se já respondeu, desconsidere esta mensagem.\n\n---- Pedido original ----\n\n" + r.getBody();
            case OVERDUE -> "O prazo para o retorno deste pedido venceu em " + due
                    + " e ainda não recebemos a resposta. A cobrança dos títulos segue suspensa até a conclusão da "
                    + "análise; pedimos o retorno com urgência.\n\n---- Pedido original ----\n\n" + r.getBody();
        };
    }
}
