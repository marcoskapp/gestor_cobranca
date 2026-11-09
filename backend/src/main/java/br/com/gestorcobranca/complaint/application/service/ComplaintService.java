package br.com.gestorcobranca.complaint.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases;
import br.com.gestorcobranca.complaint.application.port.out.ComplaintQueries;
import br.com.gestorcobranca.complaint.domain.model.Complaint;
import br.com.gestorcobranca.complaint.domain.model.ComplaintChannel;
import br.com.gestorcobranca.complaint.domain.model.ComplaintDeadlineRule;
import br.com.gestorcobranca.complaint.domain.model.ComplaintEvidence;
import br.com.gestorcobranca.complaint.domain.model.ComplaintOutcome;
import br.com.gestorcobranca.complaint.domain.model.ComplaintStatus;
import br.com.gestorcobranca.complaint.domain.model.ComplaintSubject;
import br.com.gestorcobranca.complaint.domain.repository.ComplaintDeadlineRuleRepository;
import br.com.gestorcobranca.complaint.domain.repository.ComplaintEvidenceRepository;
import br.com.gestorcobranca.complaint.domain.repository.ComplaintRepository;
import br.com.gestorcobranca.complaint.domain.service.ComplaintDeadlines;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.identity.application.port.in.UserDisplayNames;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileSignatures;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
class ComplaintService implements ComplaintUseCases {

    static final long MAX_EVIDENCE_BYTES = MAX_EVIDENCE_MB * 1024L * 1024L;

    private final ComplaintRepository complaints;
    private final ComplaintEvidenceRepository evidences;
    private final ComplaintDeadlineRuleRepository rules;
    private final ComplaintQueries queries;
    private final DebtorUseCases debtors;
    private final CaseUseCases cases;
    private final UserDirectory users;
    private final UserDisplayNames names;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final FileStorage fileStorage;
    private final ComplaintFileCleanup fileCleanup;
    private final JobQueue jobQueue;
    private final ComplaintMetrics metrics;
    private final Clock clock;

    ComplaintService(ComplaintRepository complaints, ComplaintEvidenceRepository evidences,
                     ComplaintDeadlineRuleRepository rules, ComplaintQueries queries, DebtorUseCases debtors,
                     CaseUseCases cases, UserDirectory users, UserDisplayNames names, AccessScope accessScope,
                     CurrentActor currentActor, AccessLogger accessLogger, AuditTrail auditTrail,
                     BusinessEventLogger businessEvents, FileStorage fileStorage, ComplaintFileCleanup fileCleanup,
                     JobQueue jobQueue, ComplaintMetrics metrics, Clock clock) {
        this.complaints = complaints;
        this.evidences = evidences;
        this.rules = rules;
        this.queries = queries;
        this.debtors = debtors;
        this.cases = cases;
        this.users = users;
        this.names = names;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.fileStorage = fileStorage;
        this.fileCleanup = fileCleanup;
        this.jobQueue = jobQueue;
        this.metrics = metrics;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ registro e tratamento

    @Override
    public ComplaintView register(RegisterComplaintCommand command) {
        requireRegister();
        Guard.require(command != null && command.creditorId() != null, "complaint.creditor_required",
                "Credor obrigatório");
        // comando: credor fora do escopo é 403 (com SCOPE_DENIED no log de acesso), não 404
        accessScope.requireCreditor(command.creditorId());
        ComplaintChannel channel = ComplaintEnums.parse(ComplaintChannel.class, command.channel(),
                "complaint.channel_invalid", "Canal");
        ComplaintSubject subject = ComplaintEnums.parse(ComplaintSubject.class, command.subject(),
                "complaint.subject_invalid", "Assunto");
        UUID debtorId = linkedDebtor(command.creditorId(), command.debtorId(), command.caseId());
        Instant now = Instant.now(clock);
        Instant receivedAt = command.receivedAt() == null ? now : command.receivedAt();
        Guard.require(!receivedAt.isAfter(now.plusSeconds(60)), "complaint.received_in_future",
                "A data de recebimento não pode ser futura");
        LocalDate receivedOn = receivedAt.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
        ComplaintDeadlines.Resolved deadline = ComplaintDeadlines.resolve(channel, receivedOn,
                command.responseDueDate(), rules.findAll());
        requireUniqueProtocol(channel, command.externalProtocol(), null);
        Complaint complaint = complaints.save(new Complaint(command.creditorId(), debtorId, command.caseId(), channel,
                command.externalProtocol(), subject, command.summary(), receivedAt, deadline.dueDate(),
                deadline.source(), deadline.warningDays(), currentActor.actorId()));
        if (command.assigneeId() != null) {
            accessScope.requirePermission(Permission.COMPLAINT_MANAGE);
            requireEligible(command.assigneeId(), complaint.getCreditorId());
            complaint.assign(command.assigneeId(), now);
            complaints.save(complaint);
        }
        auditTrail.record(AuditEntry.of("complaint.registered", "complaint", complaint.getId())
                .creditor(complaint.getCreditorId())
                .created("channel", channel)
                .created("subject", subject)
                .created("debtorId", complaint.getDebtorId())
                .created("caseId", complaint.getCaseId())
                .created("externalProtocol", complaint.getExternalProtocol())
                .created("receivedAt", complaint.getReceivedAt())
                .created("dueDate", complaint.getDueDate())
                .created("deadlineSource", complaint.getDeadlineSource())
                .created("assigneeId", complaint.getAssigneeId()));
        businessEvents.log(ComplaintEvents.base(BusinessEventTypes.COMPLAINT_REGISTERED, complaint)
                .attr("deadlineSource", complaint.getDeadlineSource())
                .attr("responseDays", ChronoUnit.DAYS.between(receivedOn, complaint.getDueDate()))
                .attr("withCase", complaint.getCaseId() != null)
                .attr("withDebtor", complaint.getDebtorId() != null)
                .attr("hoursToRegister", ChronoUnit.HOURS.between(receivedAt, now)));
        metrics.registered(channel.name());
        return view(complaint, today());
    }

    @Override
    public ComplaintView reclassify(UUID complaintId, ReclassifyCommand command) {
        requireRegister();
        Guard.require(command != null, "complaint.required", "Dados obrigatórios");
        Complaint complaint = loadVisible(complaintId);
        ComplaintSubject subject = ComplaintEnums.parse(ComplaintSubject.class, command.subject(),
                "complaint.subject_invalid", "Assunto");
        UUID debtorId = linkedDebtor(complaint.getCreditorId(), command.debtorId(), command.caseId());
        requireUniqueProtocol(complaint.getChannel(), command.externalProtocol(), complaint.getId());
        UUID beforeDebtor = complaint.getDebtorId();
        UUID beforeCase = complaint.getCaseId();
        String beforeProtocol = complaint.getExternalProtocol();
        ComplaintSubject beforeSubject = complaint.getSubject();
        complaint.reclassify(debtorId, command.caseId(), command.externalProtocol(), subject);
        complaints.save(complaint);
        auditTrail.record(AuditEntry.of("complaint.reclassified", "complaint", complaint.getId())
                .creditor(complaint.getCreditorId())
                .change("debtorId", beforeDebtor, complaint.getDebtorId())
                .change("caseId", beforeCase, complaint.getCaseId())
                .change("externalProtocol", beforeProtocol, complaint.getExternalProtocol())
                .change("subject", beforeSubject, complaint.getSubject()));
        return view(complaint, today());
    }

    @Override
    public ComplaintView changeDueDate(UUID complaintId, LocalDate dueDate, String reason) {
        accessScope.requirePermission(Permission.COMPLAINT_MANAGE);
        Complaint complaint = loadVisible(complaintId);
        Guard.require(reason != null && !reason.isBlank() && reason.strip().length() <= 300,
                "complaint.due_reason_required", "Motivo da mudança de prazo obrigatório (até 300 caracteres)");
        Guard.require(dueDate != null && !dueDate.isBefore(complaint.getReceivedAt()
                        .atZone(TimeConfig.BUSINESS_ZONE).toLocalDate()), "complaint.due_before_received",
                "O prazo não pode ser anterior ao recebimento");
        int warning = rules.findByChannel(complaint.getChannel()).map(ComplaintDeadlineRule::getWarningDays)
                .orElse(ComplaintDeadlines.DEFAULT_WARNING_DAYS);
        LocalDate previous = complaint.changeDueDate(dueDate, warning);
        complaints.save(complaint);
        auditTrail.record(AuditEntry.of("complaint.due_changed", "complaint", complaint.getId())
                .creditor(complaint.getCreditorId())
                .reason(reason.strip())
                .change("dueDate", previous, complaint.getDueDate()));
        return view(complaint, today());
    }

    @Override
    public ComplaintView assign(UUID complaintId, UUID assigneeId) {
        accessScope.requirePermission(Permission.COMPLAINT_MANAGE);
        Complaint complaint = loadVisible(complaintId);
        if (assigneeId != null) {
            requireEligible(assigneeId, complaint.getCreditorId());
        }
        UUID previous = complaint.assign(assigneeId, Instant.now(clock));
        complaints.save(complaint);
        recordAssignment(complaint, previous, "manual");
        return view(complaint, today());
    }

    @Override
    public ComplaintView start(UUID complaintId) {
        accessScope.requirePermission(Permission.COMPLAINT_MANAGE);
        Complaint complaint = loadVisible(complaintId);
        complaint.start();
        if (complaint.getAssigneeId() == null) {
            UUID previous = complaint.assign(currentActor.actorId(), Instant.now(clock));
            recordAssignment(complaint, previous, "take");
        }
        complaints.save(complaint);
        auditTrail.record(AuditEntry.of("complaint.started", "complaint", complaint.getId())
                .creditor(complaint.getCreditorId())
                .change("status", ComplaintStatus.RECEIVED, complaint.getStatus()));
        return view(complaint, today());
    }

    @Override
    public ComplaintView answer(UUID complaintId, String response, Instant answeredAt) {
        accessScope.requirePermission(Permission.COMPLAINT_MANAGE);
        Complaint complaint = loadVisible(complaintId);
        Instant now = Instant.now(clock);
        Instant at = answeredAt == null ? now : answeredAt;
        Guard.require(!at.isAfter(now.plusSeconds(60)), "complaint.answer_in_future",
                "A data da resposta não pode ser futura");
        ComplaintStatus before = complaint.getStatus();
        complaint.answer(response, at, currentActor.actorId(), TimeConfig.BUSINESS_ZONE);
        if (complaint.getAssigneeId() == null) {
            UUID previous = complaint.assign(currentActor.actorId(), now);
            recordAssignment(complaint, previous, "take");
        }
        complaints.save(complaint);
        auditTrail.record(AuditEntry.of("complaint.answered", "complaint", complaint.getId())
                .creditor(complaint.getCreditorId())
                .change("status", before, complaint.getStatus())
                .created("answeredAt", complaint.getAnsweredAt())
                .created("answeredOnTime", complaint.getAnsweredOnTime())
                .meta("responseLength", complaint.getResponse().length()));
        LocalDate answeredOn = at.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
        businessEvents.log(ComplaintEvents.base(BusinessEventTypes.COMPLAINT_ANSWERED, complaint)
                .attr("onTime", complaint.getAnsweredOnTime())
                .attr("daysToAnswer", ChronoUnit.DAYS.between(complaint.getReceivedAt()
                        .atZone(TimeConfig.BUSINESS_ZONE).toLocalDate(), answeredOn))
                .attr("daysLate", Math.max(0, ChronoUnit.DAYS.between(complaint.getDueDate(), answeredOn))));
        metrics.answered(complaint.getChannel().name(), Boolean.TRUE.equals(complaint.getAnsweredOnTime()));
        return view(complaint, today());
    }

    @Override
    public ComplaintView close(UUID complaintId, String outcome) {
        accessScope.requirePermission(Permission.COMPLAINT_MANAGE);
        Complaint complaint = loadVisible(complaintId);
        ComplaintOutcome result = ComplaintEnums.parse(ComplaintOutcome.class, outcome, "complaint.outcome_invalid",
                "Resultado");
        Instant now = Instant.now(clock);
        complaint.close(result, now, currentActor.actorId());
        complaints.save(complaint);
        auditTrail.record(AuditEntry.of("complaint.closed", "complaint", complaint.getId())
                .creditor(complaint.getCreditorId())
                .change("status", ComplaintStatus.ANSWERED, ComplaintStatus.CLOSED)
                .created("outcome", result));
        businessEvents.log(ComplaintEvents.base(BusinessEventTypes.COMPLAINT_CLOSED, complaint)
                .attr("outcome", result)
                .attr("onTime", complaint.getAnsweredOnTime())
                .attr("daysOpen", ChronoUnit.DAYS.between(complaint.getReceivedAt(), now)));
        metrics.closed(complaint.getChannel().name(), result.name());
        return view(complaint, today());
    }

    // ------------------------------------------------------------------ consulta

    @Override
    @Transactional(readOnly = true)
    public ComplaintDetail detail(UUID complaintId) {
        requireRegister();
        Complaint complaint = loadVisible(complaintId);
        accessLogger.log(AccessEvent.of(AccessEventType.SENSITIVE_DATA_VIEWED, currentActor.get(), Map.of(
                "resource", "complaint", "resourceId", complaintId.toString(), "field", "summary,response")));
        Map<UUID, String> who = names(Arrays.asList(complaint.getRegisteredBy(), complaint.getAnsweredBy(),
                complaint.getAssigneeId()));
        return new ComplaintDetail(view(complaint, today(), who, null), complaint.getSummary(), complaint.getResponse(),
                complaint.getRegisteredBy(), who.get(complaint.getRegisteredBy()), complaint.getAnsweredBy(),
                who.get(complaint.getAnsweredBy()));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ComplaintView> search(ComplaintQuery query, PageQuery page) {
        requireRegister();
        ComplaintQuery q = query == null ? new ComplaintQuery(null, null, null, null, null, null, false, null, null) : query;
        if (q.creditorId() != null) {
            accessScope.requireVisible("creditor", q.creditorId(), q.creditorId());
        }
        if (q.debtorId() != null) {
            debtors.get(q.debtorId());
        }
        LocalDate today = today();
        Set<ComplaintStatus> statuses = ComplaintEnums.parseSet(ComplaintStatus.class, q.statuses(),
                "complaint.status_invalid", "Situação");
        Boolean overdue = null;
        LocalDate dueUntil = null;
        if ("overdue".equals(q.deadline())) {
            overdue = true;
        } else if ("on_time".equals(q.deadline())) {
            overdue = false;
        } else if ("soon".equals(q.deadline())) {
            dueUntil = today;
        } else if (q.deadline() != null && !q.deadline().isBlank()) {
            throw new BusinessRuleException("complaint.deadline_filter_invalid", "Filtro de prazo inválido");
        }
        UUID assignee = "me".equals(q.assignee()) ? currentActor.actorId()
                : q.assignee() == null || q.assignee().isBlank() ? null : parseUuid(q.assignee());
        ComplaintRepository.Criteria criteria = new ComplaintRepository.Criteria(
                accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of()), q.creditorId(),
                statuses, ComplaintEnums.parseSet(ComplaintChannel.class, q.channels(), "complaint.channel_invalid",
                "Canal"), q.debtorId(), q.caseId(), assignee, q.unassigned(), today, overdue, dueUntil,
                q.externalProtocol() == null || q.externalProtocol().isBlank() ? null : q.externalProtocol().strip());
        Page<Complaint> found = complaints.search(criteria, page.toPageable(Sort.by("dueDate").and(Sort.by("id"))));
        Map<UUID, String> who = names(found.stream().map(Complaint::getAssigneeId).toList());
        Map<UUID, Integer> counts = queries.evidenceCounts(found.stream().map(Complaint::getId).toList());
        return PageResponse.of(found, c -> view(c, today, who, counts));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssigneeOption> assigneeOptions(UUID complaintId) {
        accessScope.requirePermission(Permission.COMPLAINT_MANAGE);
        Complaint complaint = loadVisible(complaintId);
        List<UserSummary> eligible = users.activeUsersWithPermission(Permission.COMPLAINT_MANAGE.name()).stream()
                .filter(u -> u.canAccessCreditor(complaint.getCreditorId()))
                .sorted(Comparator.comparing(UserSummary::name)).toList();
        Map<UUID, Long> load = queries.openByAssignees(eligible.stream().map(UserSummary::id).toList());
        return eligible.stream().map(u -> new AssigneeOption(u.id(), u.name(), load.getOrDefault(u.id(), 0L))).toList();
    }

    // ------------------------------------------------------------------ evidências

    @Override
    public EvidenceView attachEvidence(UUID complaintId, EvidenceUpload upload) {
        requireRegister();
        Complaint complaint = loadVisible(complaintId);
        Guard.requireState(complaint.getStatus() != ComplaintStatus.CLOSED, "complaint.closed", "Reclamação encerrada");
        Guard.require(upload != null && upload.content() != null && upload.size() > 0, "complaint.evidence_empty",
                "Arquivo vazio");
        Guard.require(upload.size() <= MAX_EVIDENCE_BYTES, "complaint.evidence_too_large",
                "Arquivo excede o tamanho máximo de %d MB".formatted(MAX_EVIDENCE_MB));
        long inUse = evidences.countByComplaintIdAndStatusIn(complaint.getId(),
                EnumSet.of(ComplaintEvidence.Status.PENDING_SCAN, ComplaintEvidence.Status.AVAILABLE));
        if (inUse >= ComplaintEvidence.MAX_PER_COMPLAINT) {
            throw new ConflictException("complaint.evidence_limit", "Limite de %d evidências por reclamação"
                    .formatted(ComplaintEvidence.MAX_PER_COMPLAINT));
        }
        FileSignatures.Sniffed sniffed = FileSignatures.sniff(upload.content());
        String contentType = sniffed.contentType().orElseThrow(() -> new BusinessRuleException(
                "complaint.evidence_type_invalid", "Evidência deve ser PDF, PNG ou JPEG (conferido pelo conteúdo)"));
        StoredFile stored = fileStorage.store(FileCategory.QUARANTINE, upload.fileName(), contentType, sniffed.content(),
                upload.size());
        fileCleanup.deleteOnRollback(stored.key());
        ComplaintEvidence evidence = evidences.save(new ComplaintEvidence(complaint, stored.key(),
                stored.originalName(), contentType, stored.size(), stored.sha256()));
        auditTrail.record(AuditEntry.of("complaint.evidence_quarantined", "complaint", complaint.getId())
                .creditor(complaint.getCreditorId())
                .created("evidenceId", evidence.getId())
                .created("fileName", evidence.getFileName())
                .created("sha256", evidence.getSha256())
                .meta("size", evidence.getSize()));
        jobQueue.enqueue(ComplaintEvidenceScanJob.TYPE, new ComplaintEvidenceScanJob.Payload(evidence.getId()), null,
                "complaint_evidence_scan:" + evidence.getId());
        return evidenceView(evidence, names(List.of(currentActor.actorId())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvidenceView> evidences(UUID complaintId) {
        requireRegister();
        Complaint complaint = loadVisible(complaintId);
        List<ComplaintEvidence> list = evidences.findByComplaintIdOrderByCreatedAtAsc(complaint.getId());
        Map<UUID, String> who = names(list.stream().map(ComplaintEvidence::getCreatedBy).toList());
        return list.stream().map(e -> evidenceView(e, who)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public EvidenceContent openEvidence(UUID complaintId, UUID evidenceId) {
        requireRegister();
        Complaint complaint = loadVisible(complaintId);
        ComplaintEvidence evidence = evidences.findById(evidenceId)
                .filter(e -> e.getComplaintId().equals(complaint.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("complaint_evidence", evidenceId));
        Guard.requireState(evidence.getStatus() == ComplaintEvidence.Status.AVAILABLE, "complaint.evidence_unavailable",
                "Evidência ainda em verificação ou recusada");
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(), Map.of(
                "resource", "complaint_evidence", "resourceId", evidenceId.toString(),
                "category", FileCategory.EVIDENCE.name())));
        return new EvidenceContent(evidence.getFileName(), evidence.getContentType(), evidence.getSize(),
                fileStorage.open(evidence.getFileKey()));
    }

    // ------------------------------------------------------------------ configuração e indicador

    @Override
    @Transactional(readOnly = true)
    public List<DeadlineRuleView> deadlineRules() {
        requireRegister();
        Map<ComplaintChannel, ComplaintDeadlineRule> byChannel = rules.findAll().stream()
                .collect(Collectors.toMap(ComplaintDeadlineRule::getChannel, Function.identity()));
        return Arrays.stream(ComplaintChannel.values()).map(ch -> {
            ComplaintDeadlineRule r = byChannel.get(ch);
            return r == null ? new DeadlineRuleView(ch.name(), ComplaintDeadlines.DEFAULT_DAYS,
                    ComplaintDeadlines.DEFAULT_WARNING_DAYS, true)
                    : new DeadlineRuleView(ch.name(), r.getResponseDays(), r.getWarningDays(), false);
        }).toList();
    }

    @Override
    public DeadlineRuleView updateDeadlineRule(String channel, int responseDays, int warningDays) {
        accessScope.requirePermission(Permission.COMPLAINT_MANAGE);
        // a regra vale para todos os credores da assessoria: só quem tem acesso a todos
        accessScope.requireAllCreditors("complaint_deadline_rule");
        ComplaintChannel ch = ComplaintEnums.parse(ComplaintChannel.class, channel, "complaint.channel_invalid", "Canal");
        ComplaintDeadlineRule rule = rules.findByChannel(ch).orElse(null);
        Integer beforeDays = rule == null ? null : rule.getResponseDays();
        Integer beforeWarning = rule == null ? null : rule.getWarningDays();
        if (rule == null) {
            rule = new ComplaintDeadlineRule(ch, responseDays, warningDays);
        } else {
            rule.change(responseDays, warningDays);
        }
        ComplaintDeadlineRule saved = rules.save(rule);
        auditTrail.record(AuditEntry.of("complaint.deadline_rule_changed", "complaint_deadline_rule", saved.getId())
                .change("responseDays", beforeDays, saved.getResponseDays())
                .change("warningDays", beforeWarning, saved.getWarningDays())
                .meta("channel", ch));
        return new DeadlineRuleView(ch.name(), saved.getResponseDays(), saved.getWarningDays(), false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditorIndicator> indicatorByCreditor(LocalDate from, LocalDate to) {
        accessScope.requirePermission(Permission.COMPLAINT_MANAGE);
        LocalDate today = today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        Guard.require(!start.isAfter(end), "complaint.period_invalid", "Período inválido");
        Guard.require(ChronoUnit.DAYS.between(start, end) <= 366, "complaint.period_too_long",
                "Período de até 12 meses");
        return queries.indicatorByCreditor(accessScope.hasAllCreditors() ? null
                        : accessScope.restrictCreditors(List.of()),
                start.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant(),
                end.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant(), today);
    }

    // ------------------------------------------------------------------ apoio

    /** Devedor do vínculo: o do caso (que precisa ser do credor) ou o informado (visível ao ator). */
    private UUID linkedDebtor(UUID creditorId, UUID debtorId, UUID caseId) {
        if (caseId != null) {
            CaseView c = cases.get(caseId);
            Guard.require(c.creditorId().equals(creditorId), "complaint.case_creditor_mismatch",
                    "O caso não é do credor da reclamação");
            Guard.require(debtorId == null || debtorId.equals(c.debtorId()), "complaint.case_debtor_mismatch",
                    "O caso não é do devedor informado");
            return c.debtorId();
        }
        if (debtorId != null) {
            debtors.get(debtorId);
        }
        return debtorId;
    }

    private void requireUniqueProtocol(ComplaintChannel channel, String protocol, UUID self) {
        if (protocol == null || protocol.isBlank()) {
            return;
        }
        boolean taken = complaints.search(new ComplaintRepository.Criteria(null, null, null, Set.of(channel), null,
                        null, null, false, null, null, null, protocol.strip()),
                PageQuery.of(0, 2).toPageable(Sort.by("id"))).stream().anyMatch(c -> !c.getId().equals(self));
        if (taken) {
            // mensagem neutra: não revela credor nem dados da reclamação existente (pode estar fora do escopo do ator)
            throw new ConflictException("complaint.protocol_taken",
                    "Este protocolo já está registrado para o canal; confira o número ou fale com a supervisão");
        }
    }

    private void recordAssignment(Complaint complaint, UUID previous, String mode) {
        auditTrail.record(AuditEntry.of("complaint.assigned", "complaint", complaint.getId())
                .creditor(complaint.getCreditorId())
                .change("assigneeId", previous, complaint.getAssigneeId())
                .meta("mode", mode));
        businessEvents.log(ComplaintEvents.base(BusinessEventTypes.COMPLAINT_ASSIGNED, complaint)
                .attr("mode", mode).attr("unassigned", complaint.getAssigneeId() == null));
    }

    private void requireEligible(UUID userId, UUID creditorId) {
        UserSummary user = users.find(userId).orElseThrow(() -> new BusinessRuleException("complaint.assignee_invalid",
                "Responsável inexistente"));
        Guard.require(user.active() && user.canAccessCreditor(creditorId)
                        && user.permissions().contains(Permission.COMPLAINT_MANAGE.name()),
                "complaint.assignee_invalid", "O responsável precisa estar ativo, tratar reclamações e ter acesso ao credor");
    }

    private Complaint loadVisible(UUID complaintId) {
        Complaint complaint = complaints.findById(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException("complaint", complaintId));
        accessScope.requireVisible("complaint", complaintId, complaint.getCreditorId());
        return complaint;
    }

    private void requireRegister() {
        Actor actor = currentActor.get();
        if (!actor.has(Permission.COMPLAINT_MANAGE)) {
            accessScope.requirePermission(Permission.COMPLAINT_REGISTER);
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("complaint.assignee_invalid", "Responsável inválido");
        }
    }

    private Map<UUID, String> names(List<UUID> ids) {
        Set<UUID> distinct = new HashSet<>(ids);
        distinct.remove(null);
        if (distinct.isEmpty()) {
            return new java.util.HashMap<>();
        }
        Map<UUID, String> out = new java.util.HashMap<>();
        names.resolve(distinct.stream().limit(UserDisplayNames.MAX_IDS).toList())
                .forEach(n -> out.put(n.id(), n.name()));
        return out;
    }

    private ComplaintView view(Complaint c, LocalDate today) {
        return view(c, today, names(Arrays.asList(c.getAssigneeId())), null);
    }

    private ComplaintView view(Complaint c, LocalDate today, Map<UUID, String> who, Map<UUID, Integer> counts) {
        int evidenceCount = counts != null ? counts.getOrDefault(c.getId(), 0)
                : queries.evidenceCounts(List.of(c.getId())).getOrDefault(c.getId(), 0);
        Long daysToDue = c.getStatus().awaitingAnswer() ? ChronoUnit.DAYS.between(today, c.getDueDate()) : null;
        return new ComplaintView(c.getId(), c.getCreditorId(), c.getDebtorId(), c.getCaseId(), c.getChannel().name(),
                c.getExternalProtocol(), c.getSubject().name(), c.getStatus().name(), c.getReceivedAt(), c.getDueDate(),
                c.getDeadlineSource().name(), c.deadlineState(today), daysToDue, c.getAssigneeId(),
                who.get(c.getAssigneeId()), c.getAnsweredAt(), c.getAnsweredOnTime(),
                c.getOutcome() == null ? null : c.getOutcome().name(), c.getClosedAt(), evidenceCount,
                c.getVersion() == null ? 0 : c.getVersion());
    }

    private static EvidenceView evidenceView(ComplaintEvidence e, Map<UUID, String> who) {
        return new EvidenceView(e.getId(), e.getComplaintId(), e.getFileName(), e.getContentType(), e.getSize(),
                e.getSha256(), e.getStatus().name(), e.getVerdict() == null ? null : e.getVerdict().name(),
                e.getCreatedAt(), e.getCreatedBy(), who.get(e.getCreatedBy()));
    }
}
