package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DebtorNoticeCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DebtorNoticeDraft;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputedTitle;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.NoticeChannelOption;
import br.com.gestorcobranca.collection.application.port.spi.DisputeDebtorMessenger;
import br.com.gestorcobranca.collection.application.port.spi.DisputeDebtorMessenger.Notice;
import br.com.gestorcobranca.collection.application.port.spi.DisputeDebtorMessenger.Sent;
import br.com.gestorcobranca.collection.domain.model.DebtorNoticeChannel;
import br.com.gestorcobranca.collection.domain.model.DebtorNoticeDelivery;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeDebtorNotice;
import br.com.gestorcobranca.collection.domain.model.DisputeStatus;
import br.com.gestorcobranca.collection.domain.model.DisputeTemplateKind;
import br.com.gestorcobranca.collection.domain.repository.DisputeDebtorNoticeRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.collection.domain.service.DisputeTemplates;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resposta fundamentada ao devedor sobre o resultado da contestação (CDC): texto pelo modelo do resultado, editado
 * pelo analista, enviado como mensagem <b>transacional</b> pelo communication (porta {@link DisputeDebtorMessenger})
 * — passa pelo porteiro de contato com a categoria própria — ou registrado como entregue por outro meio (carta,
 * presencial, protocolo do órgão). A contestação passa a "resposta enviada" quando a mensagem sai (ou no registro).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class DisputeDebtorResponder {

    private final DisputeRepository disputes;
    private final DisputeDebtorNoticeRepository notices;
    private final DisputeTemplateProvider templates;
    private final ObjectProvider<DisputeDebtorMessenger> messenger;
    private final DebtorUseCases debtors;
    private final CreditorUseCases creditors;
    private final TenantAdministration tenants;
    private final DisputeCreditorChannel channel;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final CollectionMetrics metrics;
    private final Clock clock;

    DisputeDebtorResponder(DisputeRepository disputes, DisputeDebtorNoticeRepository notices,
                           DisputeTemplateProvider templates, ObjectProvider<DisputeDebtorMessenger> messenger,
                           DebtorUseCases debtors, CreditorUseCases creditors, TenantAdministration tenants,
                           DisputeCreditorChannel channel, CurrentActor currentActor, AuditTrail auditTrail,
                           BusinessEventLogger businessEvents, CollectionMetrics metrics, Clock clock) {
        this.disputes = disputes;
        this.notices = notices;
        this.templates = templates;
        this.messenger = messenger;
        this.debtors = debtors;
        this.creditors = creditors;
        this.tenants = tenants;
        this.channel = channel;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    DebtorNoticeDraft draft(Dispute d, List<DisputedTitle> titles) {
        requireResolved(d);
        DisputeTemplateProvider.Current template = templates.current(DisputeTemplateKind.DEBTOR_RESPONSE,
                d.getOutcome().name());
        Map<String, String> vars = variables(d, titles);
        List<NoticeChannelOption> channels = messenger().check(d.getDebtorId(), d.getCreditorId()).stream()
                .map(c -> new NoticeChannelOption(c.channel(), c.allowed(), c.reasons(), c.nextAllowedAt(),
                        c.contactId(), c.maskedContact()))
                .toList();
        return new DebtorNoticeDraft(template.key(), template.version(), DisputeTemplates.fill(template.subject(), vars),
                DisputeTemplates.fill(template.body(), vars), channels);
    }

    DisputeDebtorNotice send(Dispute d, DebtorNoticeCommand command) {
        Guard.require(command != null, "dispute_notice.required", "Dados da resposta obrigatórios");
        requireResolved(d);
        DebtorNoticeChannel channelType = CollectionEnums.parse(DebtorNoticeChannel.class, command.channel(),
                "dispute_notice.channel_invalid", "Canal");
        String body = command.body() == null ? "" : command.body().trim();
        String subject = command.subject() == null ? null : command.subject().trim();
        Guard.require(!DisputeTemplates.hasPlaceholders(body) && !DisputeTemplates.hasPlaceholders(subject),
                "dispute_notice.placeholder_left", "Há variáveis do modelo sem preencher no texto");
        DisputeTemplateProvider.Current template = templates.current(DisputeTemplateKind.DEBTOR_RESPONSE,
                d.getOutcome().name());
        Instant now = Instant.now(clock);
        DisputeDebtorNotice notice;
        String outcome;
        if (channelType == DebtorNoticeChannel.OTHER) {
            notice = notices.save(new DisputeDebtorNotice(d.getId(), channelType, null, DebtorNoticeDelivery.REGISTERED,
                    null, template.key(), template.version(), subject, body, currentActor.actorId(), now));
            markNotified(d);
            outcome = "registered";
        } else {
            Guard.require(channelType != DebtorNoticeChannel.EMAIL || (subject != null && !subject.isBlank()),
                    "dispute_notice.subject_required", "Assunto obrigatório no e-mail");
            Sent sent = messenger().send(new Notice(d.getId(), d.getDebtorId(), d.getCreditorId(), d.getCaseId(),
                    channelType.name(), command.contactId(), subject, body));
            boolean blocked = "FAILED".equals(sent.status());
            notice = notices.save(new DisputeDebtorNotice(d.getId(), channelType, sent.messageId(),
                    blocked ? DebtorNoticeDelivery.BLOCKED : DebtorNoticeDelivery.QUEUED,
                    blocked ? sent.failureDetail() : null, template.key(), template.version(), subject, body,
                    currentActor.actorId(), now));
            outcome = blocked ? "blocked" : "queued";
        }
        auditTrail.record(AuditEntry.of("dispute.debtor_notice", "dispute", d.getId())
                .creditor(d.getCreditorId())
                .created("noticeId", notice.getId())
                .created("channel", channelType)
                .created("status", notice.getStatus())
                .meta("messageId", notice.getMessageId())
                .meta("templateVersion", template.version()));
        businessEvents.log(DisputeEvents.base(BusinessEventTypes.DISPUTE_DEBTOR_NOTIFIED, d)
                .channel(channelType.name())
                .attr("outcome", outcome)
                .attr("disputeOutcome", d.getOutcome())
                .attr("reasons", "blocked".equals(outcome) ? notice.getFailureDetail() : null)
                .attr("hoursAfterResolution", Duration.between(d.getResolvedAt(), now).toHours()));
        metrics.disputeDebtorNotice(channelType.name(), outcome);
        return notice;
    }

    /** Situação da mensagem no communication: enviada/entregue/lida conclui a resposta ao devedor. */
    void messageStatusChanged(MessageStatusChanged event) {
        Optional<DisputeDebtorNotice> found = notices.findByMessageId(event.messageId());
        if (found.isEmpty()) {
            return;
        }
        DisputeDebtorNotice notice = found.get();
        Optional<DisputeDebtorMessenger.Status> status = messenger().status(event.messageId());
        String code = status.map(DisputeDebtorMessenger.Status::failureCode).orElse(null);
        String detail = status.map(DisputeDebtorMessenger.Status::failureDetail).orElse(null);
        if (!notice.updateFromMessage(event.status(), code, detail, event.occurredAt())) {
            return;
        }
        notices.save(notice);
        if (notice.isDelivered()) {
            disputes.findById(notice.getDisputeId()).ifPresent(this::markNotified);
        }
        metrics.disputeDebtorNotice(notice.getChannel().name(), notice.getStatus().name().toLowerCase(java.util.Locale.ROOT));
    }

    private void markNotified(Dispute d) {
        if (d.getDebtorNoticeStatus() != br.com.gestorcobranca.collection.domain.model.DebtorNoticeStatus.SENT) {
            d.markDebtorNotified();
            disputes.save(d);
        }
    }

    private Map<String, String> variables(Dispute d, List<DisputedTitle> titles) {
        Map<String, String> vars = new HashMap<>();
        vars.put("primeiro_nome", DisputeTexts.firstName(debtors.get(d.getDebtorId()).name()));
        vars.put("credor", creditors.get(d.getCreditorId()).tradeName());
        vars.put("assessoria", tenants.currentTenant().tradeName());
        vars.put("protocolo", DisputeTexts.protocol(d.getId()));
        vars.put("tipo", DisputeTexts.type(d.getType()));
        vars.put("titulos", DisputeTexts.titles(titles, false));
        vars.put("data_abertura", DisputeTexts.date(d.getOpenedAt()));
        vars.put("resultado", DisputeTexts.outcome(d.getOutcome()));
        vars.put("fundamentacao", d.getResolutionNote());
        vars.put("responsavel", channel.responsibleName(d));
        return vars;
    }

    private static void requireResolved(Dispute d) {
        if (d.getStatus() != DisputeStatus.RESOLVED || d.getOutcome() == null) {
            throw new ConflictException("dispute.notice_before_resolution",
                    "A resposta ao devedor é enviada depois da resolução da contestação");
        }
    }

    private DisputeDebtorMessenger messenger() {
        DisputeDebtorMessenger m = messenger.getIfAvailable();
        if (m == null) {
            throw new ConflictException("dispute_notice.unavailable", "Envio de mensagens indisponível");
        }
        return m;
    }
}
