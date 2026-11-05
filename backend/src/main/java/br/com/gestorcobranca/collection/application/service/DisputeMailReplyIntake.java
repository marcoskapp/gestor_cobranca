package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.InboundMailProperties;
import br.com.gestorcobranca.collection.application.port.in.InboundMailUseCases.InboundMessage;
import br.com.gestorcobranca.collection.application.port.in.InboundMailUseCases.Outcome;
import br.com.gestorcobranca.collection.application.port.out.DisputeReplyRoutes;
import br.com.gestorcobranca.collection.application.port.out.InboundMailParser;
import br.com.gestorcobranca.collection.application.port.out.InboundMailParser.Attachment;
import br.com.gestorcobranca.collection.application.port.out.InboundMailParser.ParsedMail;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeCreditorRequest;
import br.com.gestorcobranca.collection.domain.model.DisputeMailReply;
import br.com.gestorcobranca.collection.domain.model.MailQuarantineReason;
import br.com.gestorcobranca.collection.domain.model.MailVerdict;
import br.com.gestorcobranca.collection.domain.repository.DisputeCreditorRequestRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeMailReplyRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.collection.domain.service.MailReplyScreening;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Registro, já no tenant do pedido (ator de sistema, RLS), do e-mail do credor recebido no endereço de resposta:
 * guarda o original (categoria INBOUND_MAIL, SHA-256), faz a triagem ({@link MailReplyScreening}) e, aprovado, manda os
 * anexos para a quarentena com verificação de malware. Auditoria, evento de negócio, métricas e, nas retenções por
 * segurança, log de acesso {@code INBOUND_MAIL_REJECTED}.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class DisputeMailReplyIntake {

    private static final Logger LOG = LoggerFactory.getLogger(DisputeMailReplyIntake.class);
    private static final Set<MailQuarantineReason> SECURITY = EnumSet.of(MailQuarantineReason.AUTHENTICATION_FAILED,
            MailQuarantineReason.VIRUS, MailQuarantineReason.SENDER_NOT_REGISTERED);

    private final DisputeMailReplyRepository replies;
    private final DisputeCreditorRequestRepository requests;
    private final DisputeRepository disputes;
    private final DisputeEvidenceStore evidenceStore;
    private final DisputeFileCleanup fileCleanup;
    private final FileStorage fileStorage;
    private final InboundMailParser parser;
    private final CreditorDisputeContactUseCases contacts;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final BusinessEventLogger businessEvents;
    private final CollectionMetrics metrics;
    private final InboundMailProperties inbound;
    private final Clock clock;

    DisputeMailReplyIntake(DisputeMailReplyRepository replies, DisputeCreditorRequestRepository requests,
                           DisputeRepository disputes, DisputeEvidenceStore evidenceStore,
                           DisputeFileCleanup fileCleanup, FileStorage fileStorage, InboundMailParser parser,
                           CreditorDisputeContactUseCases contacts, AuditTrail auditTrail, AccessLogger accessLogger,
                           BusinessEventLogger businessEvents, CollectionMetrics metrics, InboundMailProperties inbound,
                           Clock clock) {
        this.replies = replies;
        this.requests = requests;
        this.disputes = disputes;
        this.evidenceStore = evidenceStore;
        this.fileCleanup = fileCleanup;
        this.fileStorage = fileStorage;
        this.parser = parser;
        this.contacts = contacts;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.inbound = inbound;
        this.clock = clock;
    }

    Outcome register(DisputeReplyRoutes.Route route, InboundMessage message, ParsedMail parsed) {
        if (replies.existsByProviderMessageId(message.providerMessageId())) {
            metrics.disputeMailReceived("duplicate", "none");
            return Outcome.DUPLICATE;
        }
        DisputeCreditorRequest request = requests.findById(route.requestId()).orElseThrow(() ->
                new IllegalStateException("Rota de resposta sem pedido no tenant"));
        Dispute dispute = disputes.findById(request.getDisputeId()).orElseThrow();
        Instant now = Instant.now(clock);
        StoredFile raw = fileStorage.store(FileCategory.INBOUND_MAIL, "email-" + request.getSequenceNumber() + ".eml",
                "message/rfc822", new ByteArrayInputStream(message.raw()), message.raw().length);
        fileCleanup.deleteOnRollback(raw.key());

        String sender = parsed.from();
        boolean registered = sender != null && contacts.list(dispute.getCreditorId()).stream()
                .anyMatch(c -> c.active() && c.email() != null && c.email().trim().equalsIgnoreCase(sender));
        String senderKey = sender == null ? "(sem remetente)" : sender;
        String senderHash = DisputeCreditorChannel.sha256Bytes(senderKey.toLowerCase(Locale.ROOT)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long recent = replies.countBySenderHashAndReceivedAtAfter(senderHash, now.minus(Duration.ofHours(1)));
        DisputeMailReply.Verdicts verdicts = new DisputeMailReply.Verdicts(MailVerdict.parse(message.verdicts().spf()),
                MailVerdict.parse(message.verdicts().dkim()), MailVerdict.parse(message.verdicts().dmarc()),
                MailVerdict.parse(message.verdicts().virus()), MailVerdict.parse(message.verdicts().spam()));
        Optional<MailQuarantineReason> reason = MailReplyScreening.screen(verdicts, parsed.malformed() || sender == null,
                dispute.getStatus().isOpen(), now.isBefore(route.expiresAt()), registered, recent,
                new MailReplyScreening.Limits(inbound.perSenderPerHour(), inbound.maxMessageBytes(),
                        inbound.maxAttachments()), message.raw().length, parsed.attachmentCount());

        DisputeMailReply reply = replies.saveAndFlush(new DisputeMailReply(request, message.providerMessageId(),
                parsed.messageId() == null ? null
                        : DisputeCreditorChannel.sha256Bytes(parsed.messageId().getBytes(java.nio.charset.StandardCharsets.UTF_8)), senderKey,
                senderHash, parsed.subject(), parsed.text(), parsed.textTruncated(),
                new DisputeMailReply.RawMessage(raw.key(), raw.sha256(), raw.size()),
                message.receivedAt() == null ? now : message.receivedAt(), verdicts, parsed.attachmentCount(),
                reason.orElse(null)));
        if (reason.isEmpty()) {
            attachments(dispute, reply, parsed.attachments());
        }
        auditTrail.record(AuditEntry.of("dispute.mail_received", "dispute", dispute.getId())
                .creditor(dispute.getCreditorId())
                .created("mailReplyId", reply.getId())
                .created("requestId", request.getId())
                .created("status", reply.getStatus())
                .created("quarantineReason", reply.getQuarantineReason())
                .created("rawSha256", reply.getRawSha256())
                .meta("attachments", reply.getAttachments())
                .meta("attachmentsRejected", reply.getAttachmentsRejected())
                .meta("spf", verdicts.spf()).meta("dkim", verdicts.dkim()).meta("dmarc", verdicts.dmarc())
                .meta("virus", verdicts.virus()).meta("spam", verdicts.spam()));
        reason.filter(SECURITY::contains).ifPresent(r -> accessLogger.log(AccessEvent.anonymous(
                AccessEventType.INBOUND_MAIL_REJECTED, route.tenantId(), null,
                Map.of("resource", "dispute_mail_reply", "resourceId", reply.getId().toString(),
                        "requestId", request.getId().toString(), "reason", r.name(),
                        "senderDomain", domain(sender), "spf", verdicts.spf().name(), "dkim", verdicts.dkim().name(),
                        "dmarc", verdicts.dmarc().name(), "virus", verdicts.virus().name()))));
        LocalDate received = LocalDate.ofInstant(reply.getReceivedAt(), TimeConfig.BUSINESS_ZONE);
        var event = DisputeEvents.base(BusinessEventTypes.DISPUTE_CREDITOR_MAIL_RECEIVED, dispute)
                .attr("outcome", reason.isPresent() ? "QUARANTINED" : "ACCEPTED")
                .attr("quarantineReason", reason.map(Enum::name).orElse(null))
                .attr("sequence", request.getSequenceNumber())
                .attr("attachments", reply.getAttachments())
                .attr("attachmentsRejected", reply.getAttachmentsRejected())
                .attr("withinRequestedDeadline", !received.isAfter(request.getRequestedDueDate()));
        if (request.getSentAt() != null) {
            long hours = Math.max(0, Duration.between(request.getSentAt(), reply.getReceivedAt()).toHours());
            event.attr("responseHours", hours).durationMs(hours * 3_600_000L);
        }
        businessEvents.log(event);
        metrics.disputeMailReceived(reason.isPresent() ? "quarantined" : "accepted",
                reason.map(r -> r.name().toLowerCase(Locale.ROOT)).orElse("none"));
        LOG.atInfo().addKeyValue("mailReplyId", reply.getId()).addKeyValue("status", reply.getStatus())
                .addKeyValue("reason", reply.getQuarantineReason()).log("E-mail do credor registrado no pedido");
        return reason.isPresent() ? Outcome.QUARANTINED : Outcome.ACCEPTED;
    }

    /** Liberado da quarentena pela gestão: lê de novo o original e processa os anexos. */
    void attachmentsAfterRelease(Dispute dispute, DisputeMailReply reply) {
        byte[] raw;
        try (InputStream in = fileStorage.open(reply.getRawKey())) {
            raw = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o original do e-mail", e);
        }
        br.com.gestorcobranca.shared.domain.Guard.requireState(
                DisputeCreditorChannel.sha256Bytes(raw).equals(reply.getRawSha256()), "dispute_mail.raw_changed",
                "O original do e-mail mudou no armazenamento");
        ParsedMail parsed = parser.parse(raw, inbound.maxTextChars(), inbound.maxAttachments());
        attachments(dispute, reply, parsed.malformed() ? List.of() : parsed.attachments());
    }

    private void attachments(Dispute dispute, DisputeMailReply reply, List<Attachment> attachments) {
        int processed = 0;
        for (Attachment a : attachments) {
            if (processed >= reply.getAttachments()) {
                break;
            }
            processed++;
            String outcome = evidenceStore.quarantineMailAttachment(dispute, a.fileName(), a.content(), reply.getId());
            if (!"accepted".equals(outcome)) {
                reply.rejectAttachment();
            }
            metrics.disputeMailAttachment(outcome);
        }
        replies.save(reply);
    }

    private static String domain(String sender) {
        return sender == null || !sender.contains("@") ? "-" : sender.substring(sender.indexOf('@') + 1);
    }

}
