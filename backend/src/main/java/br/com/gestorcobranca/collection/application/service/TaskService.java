package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.TaskUseCases;
import br.com.gestorcobranca.collection.domain.model.AssignmentMode;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.CollectionTask;
import br.com.gestorcobranca.collection.domain.model.TaskStatus;
import br.com.gestorcobranca.collection.domain.model.TaskType;
import br.com.gestorcobranca.collection.domain.repository.CollectionTaskRepository;
import br.com.gestorcobranca.collection.domain.repository.InteractionRepository;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class TaskService implements TaskUseCases {

    private static final int MAX_AGENDA_DAYS = 92;
    private static final int MAX_HORIZON_DAYS = 365;

    private final CollectionTaskRepository tasks;
    private final InteractionRepository interactions;
    private final CaseAccess access;
    private final CaseAgenda agenda;
    private final CaseRecorder recorder;
    private final CallbackRecorder callbacks;
    private final SettingsProvider settings;
    private final UserDirectory users;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final Clock clock;

    TaskService(CollectionTaskRepository tasks, InteractionRepository interactions, CaseAccess access, CaseAgenda agenda,
                CaseRecorder recorder, CallbackRecorder callbacks, SettingsProvider settings, UserDirectory users,
                AccessScope accessScope, AuditTrail auditTrail, Clock clock) {
        this.tasks = tasks;
        this.interactions = interactions;
        this.access = access;
        this.agenda = agenda;
        this.recorder = recorder;
        this.callbacks = callbacks;
        this.settings = settings;
        this.users = users;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Override
    public TaskView schedule(UUID caseId, ScheduleTaskCommand command) {
        Guard.require(command != null, "task.definition_required", "Dados da tarefa obrigatórios");
        CollectionCase c = access.forCommand(caseId);
        Guard.requireState(c.getStatus().isActive(), "case.closed", "Caso encerrado");
        access.requireWorkable(c);
        TaskType type = CollectionEnums.parse(TaskType.class, command.type(), "task.type_invalid", "Tipo da tarefa");
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        CollectionTask task = create(c, type, command.dueAt(), command.assigneeUserId(), command.note(), null);
        agenda.refresh(c);
        recorder.commit(c, before, AssignmentMode.ROUTING, null, "task");
        return CollectionViews.task(task);
    }

    /**
     * Cria a tarefa (sem salvar o caso; o chamador atualiza a agenda e grava o caso).
     *
     * @param dueLocal horário no fuso de operação
     */
    CollectionTask create(CollectionCase c, TaskType type, LocalDateTime dueLocal, UUID assigneeUserId, String note,
                          UUID originInteractionId) {
        Guard.require(dueLocal != null, "task.due_required", "Data e hora da tarefa obrigatórias");
        Instant now = Instant.now(clock);
        Instant dueAt = dueLocal.atZone(TimeConfig.BUSINESS_ZONE).toInstant();
        Guard.require(!dueAt.isAfter(now.plus(MAX_HORIZON_DAYS, ChronoUnit.DAYS)), "task.due_too_far",
                "Data da tarefa além de %d dias".formatted(MAX_HORIZON_DAYS));
        UUID assignee = assigneeUserId != null ? requireAssignee(assigneeUserId, c) : defaultAssignee(c);
        CollectionTask task = tasks.save(new CollectionTask(c, type, dueAt, settings.current().callbackTolerance(), assignee,
                note, originInteractionId, now));
        auditTrail.record(AuditEntry.of("task.scheduled", "task", task.getId())
                .creditor(task.getCreditorId())
                .created("caseId", task.getCaseId())
                .created("type", task.getType())
                .created("dueAt", task.getDueAt())
                .created("assigneeUserId", task.getAssigneeUserId())
                .meta("originInteractionId", originInteractionId));
        callbacks.scheduled(task, c);
        return task;
    }

    @Override
    public TaskView complete(UUID taskId, UUID interactionId) {
        CollectionTask task = loadOpen(taskId);
        CollectionCase c = access.forCommand(task.getCaseId());
        access.requireWorkable(c);
        if (interactionId != null) {
            Guard.require(interactions.findById(interactionId).filter(i -> i.getCaseId().equals(c.getId())).isPresent(),
                    "task.interaction_invalid", "A interação não pertence ao caso da tarefa");
        }
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        completeTask(task, c, interactionId);
        agenda.refresh(c);
        recorder.commit(c, before, AssignmentMode.ROUTING, null, "task");
        return CollectionViews.task(task);
    }

    /** Conclui a tarefa com os efeitos (auditoria, evento de retorno). Não grava o caso. */
    void completeTask(CollectionTask task, CollectionCase c, UUID interactionId) {
        boolean onTime = task.complete(access.actor().id(), interactionId, Instant.now(clock));
        tasks.save(task);
        auditTrail.record(AuditEntry.of("task.completed", "task", task.getId())
                .creditor(task.getCreditorId())
                .change("status", "OPEN", task.getStatus())
                .meta("onTime", onTime)
                .meta("interactionId", interactionId));
        callbacks.completed(task, c, onTime);
    }

    @Override
    public TaskView cancel(UUID taskId, String reason) {
        CollectionTask task = loadOpen(taskId);
        CollectionCase c = access.forCommand(task.getCaseId());
        access.requireWorkable(c);
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        task.cancel(reason);
        tasks.save(task);
        auditTrail.record(AuditEntry.of("task.cancelled", "task", task.getId())
                .creditor(task.getCreditorId()).reason(task.getCancelReason())
                .change("status", "OPEN", task.getStatus()));
        callbacks.cancelled(task, c);
        agenda.refresh(c);
        recorder.commit(c, before, AssignmentMode.ROUTING, null, "task");
        return CollectionViews.task(task);
    }

    @Override
    public TaskView reschedule(UUID taskId, LocalDateTime dueAt) {
        Guard.require(dueAt != null, "task.due_required", "Data e hora da tarefa obrigatórias");
        CollectionTask task = loadOpen(taskId);
        CollectionCase c = access.forCommand(task.getCaseId());
        access.requireWorkable(c);
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        Instant previous = task.getDueAt();
        Instant now = Instant.now(clock);
        Instant newDue = dueAt.atZone(TimeConfig.BUSINESS_ZONE).toInstant();
        Guard.require(!newDue.isAfter(now.plus(MAX_HORIZON_DAYS, ChronoUnit.DAYS)), "task.due_too_far",
                "Data da tarefa além de %d dias".formatted(MAX_HORIZON_DAYS));
        task.reschedule(newDue, settings.current().callbackTolerance(), now);
        tasks.save(task);
        auditTrail.record(AuditEntry.of("task.rescheduled", "task", task.getId())
                .creditor(task.getCreditorId()).change("dueAt", previous, task.getDueAt()));
        callbacks.scheduled(task, c);
        agenda.refresh(c);
        recorder.commit(c, before, AssignmentMode.ROUTING, null, "task");
        return CollectionViews.task(task);
    }

    @Override
    public TaskView reassign(UUID taskId, UUID userId) {
        CollectionTask task = loadOpen(taskId);
        CollectionCase c = access.forCommand(task.getCaseId());
        accessScope.requirePermission(Permission.CASE_ASSIGN);
        UUID previous = task.getAssigneeUserId();
        task.reassign(userId == null ? null : requireAssignee(userId, c));
        tasks.save(task);
        auditTrail.record(AuditEntry.of("task.reassigned", "task", task.getId())
                .creditor(task.getCreditorId()).change("assigneeUserId", previous, task.getAssigneeUserId()));
        return CollectionViews.task(task);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskView> listByCase(UUID caseId) {
        CollectionCase c = access.visible(caseId);
        return tasks.findByCaseIdOrderByDueAtAsc(c.getId()).stream().map(CollectionViews::task).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TaskView> agenda(UUID userId, LocalDate from, LocalDate to, List<String> statuses,
                                         PageQuery page) {
        Actor actor = access.actor();
        UUID target = userId == null ? actor.id() : userId;
        if (!target.equals(actor.id())) {
            accessScope.requirePermission(Permission.CASE_ASSIGN);
        }
        LocalDate start = from == null ? CollectionViews.today(clock) : from;
        LocalDate end = to == null ? start : to;
        Guard.require(!end.isBefore(start) && ChronoUnit.DAYS.between(start, end) <= MAX_AGENDA_DAYS,
                "task.agenda_range_invalid", "Período da agenda inválido (até %d dias)".formatted(MAX_AGENDA_DAYS));
        Set<TaskStatus> wanted = statuses == null || statuses.isEmpty() ? EnumSet.of(TaskStatus.PENDING, TaskStatus.MISSED)
                : CollectionEnums.parseSet(TaskStatus.class, statuses, "task.status_invalid", "Status da tarefa");
        Instant fromInstant = start.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Instant toInstant = end.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        var result = tasks.findAgenda(target, fromInstant, toInstant, wanted, page.toPageable());
        // tarefas de credores fora do escopo do ator não são exibidas (agenda de outro operador)
        return new PageResponse<>(result.getContent().stream()
                .filter(t -> accessScope.canAccessCreditor(t.getCreditorId()))
                .map(CollectionViews::task).toList(), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages());
    }

    private CollectionTask loadOpen(UUID taskId) {
        CollectionTask task = tasks.findById(taskId).orElseThrow(() -> ResourceNotFoundException.of("task", taskId));
        accessScope.requireVisible("task", taskId, task.getCreditorId());
        return task;
    }

    private UUID defaultAssignee(CollectionCase c) {
        Actor actor = access.actor();
        if (c.getAssigneeUserId() != null) {
            return c.getAssigneeUserId();
        }
        return actor.isUser() ? actor.id() : null;
    }

    private UUID requireAssignee(UUID userId, CollectionCase c) {
        UserSummary user = users.find(userId).filter(UserSummary::active)
                .orElseThrow(() -> new BusinessRuleException("task.assignee_invalid", "Responsável inexistente ou inativo"));
        Guard.require(user.permissions().contains(Permission.CASE_WORK.name()) && user.canAccessCreditor(c.getCreditorId()),
                "task.assignee_invalid", "O responsável não pode trabalhar casos deste credor");
        return user.id();
    }
}
