package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.CollectionProperties;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestDraft;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorResponseCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputedTitle;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceUpload;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceView;
import br.com.gestorcobranca.collection.application.port.out.DisputeLinkRoutes;
import br.com.gestorcobranca.collection.domain.model.CreditorPosition;
import br.com.gestorcobranca.collection.domain.model.CreditorRequestStatus;
import br.com.gestorcobranca.collection.domain.model.CreditorResponseSource;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeCreditorRequest;
import br.com.gestorcobranca.collection.domain.model.DisputeCreditorResponse;
import br.com.gestorcobranca.collection.domain.model.DisputeEvidence;
import br.com.gestorcobranca.collection.domain.model.DisputeMail;
import br.com.gestorcobranca.collection.domain.model.DisputeMailPurpose;
import br.com.gestorcobranca.collection.domain.model.DisputeSettings;
import br.com.gestorcobranca.collection.domain.model.DisputeTemplateKind;
import br.com.gestorcobranca.collection.domain.repository.DisputeCreditorRequestRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeCreditorResponseRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeEvidenceRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeMailRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeSettingsRepository;
import br.com.gestorcobranca.collection.domain.service.DisputeTemplates;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases.Recipient;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases.Recipients;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Canal formal com o credor dentro da contestação: pedido pré-preenchido (modelo do tipo, contatos de contestação do
 * cadastro do credor, títulos e evidências), envio por e-mail assíncrono (job {@code collection.dispute_mail}),
 * evidências por link seguro com validade (token aleatório de 256 bits; só o hash SHA-256 fica no banco), lembrete
 * antes do prazo e cobrança do retorno vencido (acompanhamento diário) e registro da resposta do credor (manual, com
 * documentos, ou pela API pública). As respostas do credor por e-mail chegam ao responsável (resposta para o e-mail
 * dele): o sistema não tem caixa de entrada de e-mail.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class DisputeCreditorChannel {

    static final String LINK_PATH = "/api/v1/dispute-files/";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DisputeRepository disputes;
    private final DisputeCreditorRequestRepository requests;
    private final DisputeMailRepository mails;
    private final DisputeCreditorResponseRepository responses;
    private final DisputeEvidenceRepository evidences;
    private final DisputeSettingsRepository settings;
    private final DisputeTemplateProvider templates;
    private final DisputeEvidenceStore evidenceStore;
    private final DisputeLinkRoutes linkRoutes;
    private final CreditorDisputeContactUseCases contacts;
    private final CreditorUseCases creditors;
    private final TenantAdministration tenants;
    private final UserDirectory users;
    private final CurrentActor currentActor;
    private final JobQueue jobQueue;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final CollectionMetrics metrics;
    private final CollectionProperties properties;
    private final Clock clock;
    private final String baseUrl;

    DisputeCreditorChannel(DisputeRepository disputes, DisputeCreditorRequestRepository requests,
                           DisputeMailRepository mails, DisputeCreditorResponseRepository responses,
                           DisputeEvidenceRepository evidences, DisputeSettingsRepository settings,
                           DisputeTemplateProvider templates, DisputeEvidenceStore evidenceStore,
                           DisputeLinkRoutes linkRoutes, CreditorDisputeContactUseCases contacts,
                           CreditorUseCases creditors, TenantAdministration tenants, UserDirectory users,
                           CurrentActor currentActor, JobQueue jobQueue, AuditTrail auditTrail,
                           BusinessEventLogger businessEvents, CollectionMetrics metrics,
                           CollectionProperties properties, Clock clock, @Value("${app.base-url}") String baseUrl) {
        this.disputes = disputes;
        this.requests = requests;
        this.mails = mails;
        this.responses = responses;
        this.evidences = evidences;
        this.settings = settings;
        this.templates = templates;
        this.evidenceStore = evidenceStore;
        this.linkRoutes = linkRoutes;
        this.contacts = contacts;
        this.creditors = creditors;
        this.tenants = tenants;
        this.users = users;
        this.currentActor = currentActor;
        this.jobQueue = jobQueue;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    // ------------------------------------------------------------------ pedido

    CreditorRequestDraft draft(Dispute d, DisputeView view, List<EvidenceView> evidenceViews) {
        DisputeSettings.Values values = values();
        Recipients recipients = contacts.recipients(d.getCreditorId(), d.getPortfolioId());
        DisputeTemplateProvider.Current template = templates.current(DisputeTemplateKind.CREDITOR_REQUEST, d.getType().name());
        LocalDate today = CollectionViews.today(clock);
        LocalDate due = defaultDue(d, today, values);
        Map<String, String> vars = creditorVariables(d, view.titles(), due);
        // link e validade só existem no envio: as duas variáveis continuam no texto e o sistema preenche
        vars.remove("link_evidencias");
        vars.remove("validade_link");
        String subject = fillKeepingLink(template.subject(), vars);
        String body = fillKeepingLink(template.body(), vars);
        return new CreditorRequestDraft(template.key(), template.version(), subject, body,
                recipients.to().stream().map(Recipient::email).toList(),
                recipients.cc().stream().map(Recipient::email).toList(),
                responsibleEmail(d).orElse(null), true, due, view.titles(), evidenceViews,
                Instant.now(clock).plus(Duration.ofDays(values.evidenceLinkDays())), recipients.portfolioLevel());
    }

    DisputeCreditorRequest send(Dispute d, List<DisputedTitle> disputeTitles, CreditorRequestCommand command) {
        Guard.require(command != null, "dispute_request.required", "Dados do pedido obrigatórios");
        Guard.requireState(d.getStatus().isOpen(), "dispute.not_open", "Contestação já encerrada");
        DisputeSettings.Values values = values();
        LocalDate today = CollectionViews.today(clock);
        LocalDate due = command.requestedDueDate() != null ? command.requestedDueDate() : defaultDue(d, today, values);
        Guard.require(due.isAfter(today) && !due.isAfter(today.plusDays(60)), "dispute_request.due_invalid",
                "Prazo pedido ao credor entre amanhã e 60 dias");
        Set<UUID> titleIds = command.titleIds().isEmpty() ? d.getTitleIds() : command.titleIds();
        Guard.require(d.getTitleIds().containsAll(titleIds), "dispute_request.title_invalid",
                "Título fora da contestação");
        Set<UUID> evidenceIds = command.evidenceIds();
        if (!evidenceIds.isEmpty()) {
            Set<UUID> ofDispute = new java.util.HashSet<>();
            evidences.findByDisputeIdOrderByCreatedAtAsc(d.getId()).forEach(e -> ofDispute.add(e.getId()));
            Guard.require(ofDispute.containsAll(evidenceIds), "dispute_request.evidence_invalid",
                    "Evidência fora da contestação");
        }
        Recipients recipients = contacts.recipients(d.getCreditorId(), d.getPortfolioId());
        Guard.require(!recipients.to().isEmpty(), "dispute_request.no_recipient",
                "Cadastre ao menos um contato principal de contestação no credor antes de enviar o pedido");
        Optional<String> replyTo = responsibleEmail(d);
        List<String> cc = new ArrayList<>(recipients.cc().stream().map(Recipient::email).toList());
        if (command.copyAssignee()) {
            replyTo.filter(e -> !cc.contains(e)).ifPresent(cc::add);
        }
        DisputeTemplateProvider.Current template = templates.current(DisputeTemplateKind.CREDITOR_REQUEST, d.getType().name());
        Instant now = Instant.now(clock);
        Instant linkExpiresAt = evidenceIds.isEmpty() ? null : now.plus(Duration.ofDays(values.evidenceLinkDays()));
        String token = evidenceIds.isEmpty() ? null : newToken();
        Map<String, String> linkVars = new HashMap<>();
        linkVars.put("link_evidencias", token == null ? "não há documentos anexados a este pedido"
                : baseUrl + LINK_PATH + token);
        linkVars.put("validade_link", linkExpiresAt == null ? "—" : DisputeTexts.date(linkExpiresAt));
        String subject = DisputeTemplates.fill(trim(command.subject()), linkVars);
        String body = DisputeTemplates.fill(trim(command.body()), linkVars);
        Guard.require(!DisputeTemplates.hasPlaceholders(subject) && !DisputeTemplates.hasPlaceholders(body),
                "dispute_request.placeholder_left", "Há variáveis do modelo sem preencher no texto");
        int sequence = (int) requests.countByDisputeId(d.getId()) + 1;
        DisputeCreditorRequest request = requests.save(new DisputeCreditorRequest(d, sequence, template.key(),
                template.version(), recipients.to().stream().map(Recipient::email).toList(), cc, replyTo.orElse(null),
                subject, body, due, titleIds, evidenceIds, linkExpiresAt, currentActor.actorId()));
        if (token != null) {
            linkRoutes.register(sha256(token), currentActor.tenantId(), request.getId(), linkExpiresAt);
        }
        queueMail(request, DisputeMailPurpose.REQUEST);
        d.awaitCreditor(now);
        disputes.save(d);
        auditTrail.record(AuditEntry.of("dispute.creditor_requested", "dispute", d.getId())
                .creditor(d.getCreditorId())
                .created("requestId", request.getId())
                .created("sequence", sequence)
                .created("to", String.join(",", request.getTo()))
                .created("cc", String.join(",", request.getCc()))
                .created("requestedDueDate", due)
                .created("titles", titleIds.size())
                .created("evidences", evidenceIds.size())
                .meta("templateKey", template.key())
                .meta("templateVersion", template.version())
                .meta("linkExpiresAt", linkExpiresAt));
        return request;
    }

    /** Enfileira um e-mail do pedido (idempotente por propósito). */
    void queueMail(DisputeCreditorRequest request, DisputeMailPurpose purpose) {
        DisputeMail mail = mails.saveAndFlush(new DisputeMail(request.getId(), purpose));
        jobQueue.enqueue(DisputeMailJob.TYPE, new DisputeMailJob.Payload(mail.getId()), null, "dispute_mail:" + mail.getId());
    }

    // ------------------------------------------------------------------ resposta

    DisputeCreditorResponse respond(Dispute d, CreditorResponseCommand command, List<EvidenceUpload> documents,
                                    CreditorResponseSource source) {
        Guard.require(command != null, "dispute_response.required", "Dados da resposta obrigatórios");
        CreditorPosition position = CollectionEnums.parse(CreditorPosition.class, command.position(),
                "dispute_response.position_invalid", "Posição do credor");
        Instant now = Instant.now(clock);
        Instant receivedAt = command.receivedAt() == null ? now : command.receivedAt();
        Guard.require(!receivedAt.isAfter(now.plusSeconds(60)), "dispute_response.received_in_future",
                "A data de recebimento não pode ser futura");
        Guard.require(!receivedAt.isBefore(d.getOpenedAt()), "dispute_response.received_before_opening",
                "A resposta não pode ser anterior à abertura da contestação");
        List<DisputeCreditorRequest> ofDispute = requests.findByDisputeIdOrderBySequenceNumberAsc(d.getId());
        DisputeCreditorRequest answered = null;
        if (command.requestId() != null) {
            answered = ofDispute.stream().filter(r -> r.getId().equals(command.requestId())).findFirst()
                    .orElseThrow(() -> new br.com.gestorcobranca.shared.domain.BusinessRuleException(
                            "dispute_response.request_invalid", "Pedido não pertence à contestação"));
        } else {
            answered = ofDispute.stream().filter(r -> r.isAwaiting() || r.getStatus() == CreditorRequestStatus.FAILED)
                    .reduce((a, b) -> b).orElse(null);
        }
        List<EvidenceUpload> files = documents == null ? List.of() : documents;
        DisputeCreditorResponse response = responses.saveAndFlush(new DisputeCreditorResponse(d,
                answered == null ? null : answered.getId(), source, position, command.note(), receivedAt,
                currentActor.actorId()));
        List<UUID> stored = new ArrayList<>();
        if (source == CreditorResponseSource.PUBLIC_API) {
            // documentos de sistema externo: quarentena e verificação de malware antes de virar evidência
            Guard.require(files.size() <= properties.disputes().apiMaxDocuments(), "dispute_response.too_many_documents",
                    "No máximo %d documentos por resposta".formatted(properties.disputes().apiMaxDocuments()));
            for (EvidenceUpload file : files) {
                stored.add(evidenceStore.quarantine(d, file, response.getId()).getId());
            }
        } else {
            for (EvidenceUpload file : files) {
                DisputeEvidence evidence = evidenceStore.store(d, file, response.getId());
                stored.add(evidence.getId());
            }
        }
        Long responseHours = null;
        if (answered != null && answered.markAnswered(receivedAt)) {
            requests.save(answered);
            if (answered.getSentAt() != null) {
                responseHours = Duration.between(answered.getSentAt(), receivedAt).toHours();
            }
        }
        boolean stillAwaiting = ofDispute.stream().anyMatch(DisputeCreditorRequest::isAwaiting);
        if (!stillAwaiting) {
            d.stopAwaitingCreditor();
            disputes.save(d);
        }
        auditTrail.record(AuditEntry.of("dispute.creditor_responded", "dispute", d.getId())
                .creditor(d.getCreditorId())
                .created("responseId", response.getId())
                .created("position", position)
                .created("source", source)
                .created("requestId", answered == null ? null : answered.getId())
                .created("documents", stored.size())
                .meta("receivedAt", receivedAt));
        var event = DisputeEvents.base(BusinessEventTypes.DISPUTE_CREDITOR_RESPONDED, d)
                .attr("position", position)
                .attr("source", source)
                .attr("documents", stored.size())
                .attr("withRequest", answered != null)
                .attr("withinRequestedDeadline", answered == null ? null
                        : !LocalDate.ofInstant(receivedAt, br.com.gestorcobranca.shared.config.TimeConfig.BUSINESS_ZONE)
                        .isAfter(answered.getRequestedDueDate()));
        if (responseHours != null) {
            event.attr("responseHours", responseHours).durationMs(Math.max(0, responseHours) * 3_600_000L);
        }
        businessEvents.log(event);
        metrics.disputeCreditorResponse(source.name());
        return response;
    }

    /** A contestação foi encerrada: pedidos sem resposta são cancelados (sem lembretes nem cobrança). */
    void cancelPending(Dispute d) {
        for (DisputeCreditorRequest r : requests.findByDisputeIdOrderBySequenceNumberAsc(d.getId())) {
            if (r.cancel()) {
                requests.save(r);
                auditTrail.record(AuditEntry.of("dispute.creditor_request_cancelled", "dispute", d.getId())
                        .creditor(d.getCreditorId()).meta("requestId", r.getId()).change("status", "SENT", r.getStatus()));
            }
        }
    }

    // ------------------------------------------------------------------ acompanhamento

    /**
     * Lembrete antes do prazo pedido ao credor e cobrança do retorno vencido (uma vez cada).
     *
     * @return ações executadas neste lote
     */
    int followUp(int limit) {
        DisputeSettings.Values values = values();
        LocalDate today = CollectionViews.today(clock);
        LocalDate reminderUntil = values.reminderDaysBefore() == 0 ? today.minusYears(100)
                : today.plusDays(values.reminderDaysBefore());
        Instant now = Instant.now(clock);
        int done = 0;
        for (DisputeCreditorRequest r : requests.findFollowUpCandidates(reminderUntil, today, limit)) {
            if (r.flagOverdue(today, now)) {
                requests.save(r);
                queueMail(r, DisputeMailPurpose.OVERDUE);
                Dispute d = disputes.findById(r.getDisputeId()).orElseThrow();
                auditTrail.record(AuditEntry.of("dispute.creditor_request_overdue", "dispute", d.getId())
                        .creditor(d.getCreditorId()).meta("requestId", r.getId())
                        .meta("requestedDueDate", r.getRequestedDueDate()));
                businessEvents.log(DisputeEvents.base(BusinessEventTypes.DISPUTE_CREDITOR_REQUEST_OVERDUE, d)
                        .attr("sequence", r.getSequenceNumber())
                        .attr("daysLate", ChronoUnit.DAYS.between(r.getRequestedDueDate(), today)));
                metrics.disputeCreditorRequestOverdue(d.getType().name());
                done++;
            } else if (!r.getRequestedDueDate().isBefore(today) && !r.getRequestedDueDate().isAfter(reminderUntil)
                    && r.queueReminder(now)) {
                requests.save(r);
                queueMail(r, DisputeMailPurpose.REMINDER);
                done++;
            }
        }
        return done;
    }

    // ------------------------------------------------------------------ apoio

    Map<String, String> creditorVariables(Dispute d, List<DisputedTitle> titles, LocalDate due) {
        Map<String, String> vars = new HashMap<>();
        vars.put("credor", creditors.get(d.getCreditorId()).tradeName());
        vars.put("assessoria", tenants.currentTenant().tradeName());
        vars.put("protocolo", DisputeTexts.protocol(d.getId()));
        vars.put("tipo", DisputeTexts.type(d.getType()));
        vars.put("titulos", DisputeTexts.titles(titles, true));
        vars.put("valor_contestado", DisputeTexts.money(CollectionViews.amount(d.getDisputedAmount())));
        vars.put("prazo_credor", DisputeTexts.date(due));
        vars.put("data_abertura", DisputeTexts.date(d.getOpenedAt()));
        vars.put("responsavel", responsibleName(d));
        return vars;
    }

    String responsibleName(Dispute d) {
        UUID id = d.getAssigneeId() != null ? d.getAssigneeId() : currentActor.get().isUser() ? currentActor.actorId() : null;
        return id == null ? "Equipe de contestações" : users.find(id).map(UserSummary::name).orElse("Equipe de contestações");
    }

    /** E-mail do responsável (ou, sem responsável, de quem envia) para resposta e cópia. */
    Optional<String> responsibleEmail(Dispute d) {
        Actor actor = currentActor.get();
        UUID id = d.getAssigneeId() != null ? d.getAssigneeId() : actor.isUser() ? actor.id() : null;
        return id == null ? Optional.empty() : users.workEmail(id);
    }

    String senderName() {
        return tenants.currentTenant().tradeName() + " — Contestações";
    }

    private LocalDate defaultDue(Dispute d, LocalDate today, DisputeSettings.Values values) {
        LocalDate due = today.plusDays(values.creditorRequestDays());
        // o credor precisa responder antes do prazo interno da contestação, quando possível
        LocalDate limit = d.getInternalDueDate().minusDays(1);
        return limit.isAfter(today) && due.isAfter(limit) ? limit : due;
    }

    private DisputeSettings.Values values() {
        return settings.findFirstByOrderByCreatedAtAsc().map(DisputeSettings::values)
                .orElse(DisputeSettings.Values.DEFAULTS);
    }

    private static String fillKeepingLink(String text, Map<String, String> vars) {
        Map<String, String> withLink = new HashMap<>(vars);
        withLink.put("link_evidencias", "{{link_evidencias}}");
        withLink.put("validade_link", "{{validade_link}}");
        return DisputeTemplates.fill(text, withLink);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
