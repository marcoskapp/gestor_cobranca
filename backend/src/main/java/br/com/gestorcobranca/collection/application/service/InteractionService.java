package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases;
import br.com.gestorcobranca.collection.application.port.in.PromiseUseCases.PromiseView;
import br.com.gestorcobranca.collection.application.port.in.TaskUseCases.TaskView;
import br.com.gestorcobranca.collection.domain.model.AssignmentMode;
import br.com.gestorcobranca.collection.domain.model.CaseStatus;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.CollectionSettings;
import br.com.gestorcobranca.collection.domain.model.CollectionTask;
import br.com.gestorcobranca.collection.domain.model.Interaction;
import br.com.gestorcobranca.collection.domain.model.InteractionChannel;
import br.com.gestorcobranca.collection.domain.model.InteractionDirection;
import br.com.gestorcobranca.collection.domain.model.InteractionSource;
import br.com.gestorcobranca.collection.domain.model.OwnerType;
import br.com.gestorcobranca.collection.domain.model.PaymentPromise;
import br.com.gestorcobranca.collection.domain.model.Tabulation;
import br.com.gestorcobranca.collection.domain.model.TaskType;
import br.com.gestorcobranca.collection.domain.repository.CollectionQueueRepository;
import br.com.gestorcobranca.collection.domain.repository.CollectionTaskRepository;
import br.com.gestorcobranca.collection.domain.repository.InteractionRepository;
import br.com.gestorcobranca.collection.domain.repository.TabulationRepository;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.collection.InteractionRecorded;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class InteractionService implements InteractionUseCases {

    private static final Duration MAX_BACKDATE = Duration.ofDays(7);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(2);

    private final InteractionRepository interactions;
    private final TabulationRepository tabulations;
    private final CollectionTaskRepository tasks;
    private final CollectionQueueRepository queues;
    private final CaseAccess access;
    private final CaseRecorder caseRecorder;
    private final CaseAgenda agenda;
    private final TaskService taskService;
    private final PromiseService promiseService;
    private final PromiseRecorder promiseRecorder;
    private final SettingsProvider settings;
    private final UserDirectory users;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final CurrentActor currentActor;
    private final CollectionMetrics metrics;
    private final Clock clock;

    InteractionService(InteractionRepository interactions, TabulationRepository tabulations,
                       CollectionTaskRepository tasks, CollectionQueueRepository queues, CaseAccess access,
                       CaseRecorder caseRecorder, CaseAgenda agenda, TaskService taskService,
                       PromiseService promiseService, PromiseRecorder promiseRecorder, SettingsProvider settings,
                       UserDirectory users, BusinessEventLogger businessEvents, ApplicationEventPublisher events,
                       CurrentActor currentActor, CollectionMetrics metrics, Clock clock) {
        this.interactions = interactions;
        this.tabulations = tabulations;
        this.tasks = tasks;
        this.queues = queues;
        this.access = access;
        this.caseRecorder = caseRecorder;
        this.agenda = agenda;
        this.taskService = taskService;
        this.promiseService = promiseService;
        this.promiseRecorder = promiseRecorder;
        this.settings = settings;
        this.users = users;
        this.businessEvents = businessEvents;
        this.events = events;
        this.currentActor = currentActor;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public RecordResult record(UUID caseId, RecordInteractionCommand cmd) {
        Guard.require(cmd != null, "interaction.required", "Dados da interação obrigatórios");
        CollectionCase c = access.forCommand(caseId);
        InteractionSource source = Optional.ofNullable(CollectionEnums.parseOptional(InteractionSource.class, cmd.source(),
                "interaction.source_invalid", "Origem")).orElse(InteractionSource.MANUAL);
        String externalRef = cmd.externalRef() == null || cmd.externalRef().isBlank() ? null : cmd.externalRef().trim();
        if (externalRef != null) {
            Optional<Interaction> existing = interactions.findBySourceAndExternalRef(source, externalRef);
            if (existing.isPresent()) {
                Guard.requireState(existing.get().getCaseId().equals(c.getId()), "interaction.external_ref_conflict",
                        "A referência externa já foi registrada em outro caso");
                return new RecordResult(CollectionViews.interaction(existing.get()), true, null, null,
                        CollectionViews.caseView(c, CollectionViews.today(clock)));
            }
        }
        Guard.requireState(c.getStatus().isActive(), "case.closed", "Caso encerrado");
        InteractionChannel channel = CollectionEnums.parse(InteractionChannel.class, cmd.channel(),
                "interaction.channel_invalid", "Canal");
        InteractionDirection direction = CollectionEnums.parse(InteractionDirection.class, cmd.direction(),
                "interaction.direction_invalid", "Sentido");
        Tabulation tabulation = tabulation(cmd);
        requireFlagData(tabulation, cmd);
        Actor actor = currentActor.get();
        OwnerType ownerBefore = c.getOwnerType();
        boolean assume = access.requireWorkable(c);
        Instant now = Instant.now(clock);
        Instant startedAt = cmd.startedAt() == null ? now : cmd.startedAt();
        Guard.require(!startedAt.isAfter(now.plus(CLOCK_SKEW)), "interaction.started_in_future",
                "Início da interação no futuro");
        Guard.require(!startedAt.isBefore(now.minus(MAX_BACKDATE)), "interaction.started_too_old",
                "Interação com mais de 7 dias não pode ser registrada");
        UUID operatorId = actor.isUser() ? actor.id() : null;

        Interaction interaction = interactions.save(new Interaction(c, channel, direction, tabulation, cmd.note(),
                cmd.contactId(), startedAt, cmd.endedAt(), operatorId, actor.type().name(), source, externalRef));

        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        if (assume) {
            Set<UUID> userTeams = users.teamsOf(actor.id());
            UUID team = c.getQueueId() == null ? single(userTeams)
                    : queues.findById(c.getQueueId()).map(q -> teamOf(q.getTeamIds(), userTeams)).orElse(single(userTeams));
            c.assignToUser(actor.id(), team, now);
        }
        c.registerInteraction(startedAt, interaction.isEffectiveContact());
        if (interaction.isEffectiveContact() && stage(c) == CaseStatus.OPEN) {
            c.moveToStage(CaseStatus.IN_NEGOTIATION, "Contato efetivo");
        }

        CollectionSettings.Values parameters = settings.current();
        PromiseView promiseView = null;
        if (tabulation.isPromise()) {
            promiseService.cancelPending(c.getId(), "Substituída por nova promessa");
            LocalDate today = CollectionViews.today(clock);
            PaymentPromise promise = promiseRecorder.registered(new PaymentPromise(c, Money.of(cmd.promise().amount()),
                    cmd.promise().dueDate(), parameters.promiseToleranceDays(), parameters.promiseMaxDays(), today,
                    interaction.getId(), operatorId, now), c, channel.name());
            c.moveToStage(CaseStatus.PROMISE, "Promessa de pagamento");
            promiseView = CollectionViews.promise(promise);
        }
        completeCallbacks(c, cmd, interaction, parameters);
        TaskView callbackView = null;
        if (tabulation.isCallback()) {
            CollectionTask task = taskService.create(c, TaskType.CALLBACK, cmd.callback().dueAt(),
                    cmd.callback().assigneeUserId(), cmd.callback().note(), interaction.getId());
            callbackView = CollectionViews.task(task);
        }
        agenda.refresh(c);
        AssignmentMode mode = ownerBefore == OwnerType.ASSISTANT ? AssignmentMode.TAKE_OVER : AssignmentMode.INTERACTION;
        CollectionCase saved = caseRecorder.commit(c, before, mode, null, "interaction");

        publish(saved, interaction, tabulation);
        return new RecordResult(CollectionViews.interaction(interaction), false, promiseView, callbackView,
                CollectionViews.caseView(saved, CollectionViews.today(clock)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InteractionView> list(UUID caseId, PageQuery page) {
        CollectionCase c = access.visible(caseId);
        return PageResponse.of(interactions.findByCaseIdOrderByStartedAtDesc(c.getId(), page.toPageable()),
                CollectionViews::interaction);
    }

    /**
     * Conclui a tarefa indicada ou, sem indicação, os retornos em aberto cujo horário combinado
     * já chegou (considerando a tolerância antes do horário: ligar um pouco antes também cumpre).
     */
    private void completeCallbacks(CollectionCase c, RecordInteractionCommand cmd, Interaction interaction,
                                   CollectionSettings.Values parameters) {
        List<CollectionTask> toComplete = new ArrayList<>();
        if (cmd.completesTaskId() != null) {
            CollectionTask task = tasks.findById(cmd.completesTaskId())
                    .filter(t -> t.getCaseId().equals(c.getId()))
                    .orElseThrow(() -> ResourceNotFoundException.of("task", cmd.completesTaskId()));
            Guard.requireState(task.getStatus().isOpen(), "task.not_open", "Tarefa já concluída ou cancelada");
            toComplete.add(task);
        } else {
            toComplete.addAll(tasks.findOpenCallbacksDueBy(c.getId(),
                    interaction.getStartedAt().plus(parameters.callbackTolerance())));
        }
        for (CollectionTask task : toComplete) {
            taskService.completeTask(task, c, interaction.getId());
        }
    }

    private void publish(CollectionCase c, Interaction i, Tabulation t) {
        LocalDate today = CollectionViews.today(clock);
        Long durationMs = i.getDurationSeconds() == null ? null : i.getDurationSeconds() * 1000L;
        BusinessEvent event = CaseRecorder.base(BusinessEventTypes.INTERACTION_RECORDED, c, today)
                .reference("interaction", i.getId())
                .channel(i.getChannel().name())
                .attr("direction", i.getDirection())
                .attr("outcomeCode", i.getOutcomeCode())
                .attr("effectiveContact", i.isEffectiveContact())
                .attr("promise", t.isPromise())
                .attr("callback", t.isCallback())
                .attr("source", i.getSource())
                .attr("actorType", i.getActorType())
                .attr("operatorId", i.getOperatorId())
                .attr("caseStatus", c.getStatus())
                .attr("interactionNumber", c.getInteractionCount());
        if (durationMs != null) {
            event.durationMs(durationMs);
        }
        businessEvents.log(event);
        if (i.isEffectiveContact()) {
            BusinessEvent contact = CaseRecorder.base(BusinessEventTypes.CONTACT_EFFECTIVE, c, today)
                    .reference("interaction", i.getId())
                    .channel(i.getChannel().name())
                    .amount(c.getEligibleBalance())
                    .attr("direction", i.getDirection())
                    .attr("outcomeCode", i.getOutcomeCode())
                    .attr("operatorId", i.getOperatorId())
                    .attr("attemptsUntilContact", c.getInteractionCount())
                    .attr("firstEffectiveContact", c.getEffectiveContactCount() == 1);
            if (durationMs != null) {
                contact.durationMs(durationMs);
            }
            businessEvents.log(contact);
        }
        metrics.interaction(i.getChannel().name(), i.getDirection().name(), i.isEffectiveContact(), i.getSource().name());
        events.publishEvent(new InteractionRecorded(Ids.newId(), currentActor.tenantId(), i.getStartedAt(), i.getId(),
                c.getId(), c.getDebtorId(), c.getCreditorId(), i.getChannel().name(), i.getOutcomeCode(),
                i.isEffectiveContact(), i.getOperatorId(), i.getDirection().name(), c.getPortfolioId()));
    }

    private Tabulation tabulation(RecordInteractionCommand cmd) {
        Tabulation tabulation;
        if (cmd.tabulationId() != null) {
            tabulation = tabulations.findById(cmd.tabulationId())
                    .orElseThrow(() -> ResourceNotFoundException.of("tabulation", cmd.tabulationId()));
        } else if (cmd.tabulationCode() != null && !cmd.tabulationCode().isBlank()) {
            tabulation = tabulations.findByCode(cmd.tabulationCode().trim().toUpperCase(java.util.Locale.ROOT))
                    .orElseThrow(() -> new BusinessRuleException("interaction.tabulation_invalid",
                            "Tabulação inexistente: " + cmd.tabulationCode()));
        } else {
            throw new BusinessRuleException("interaction.tabulation_required", "Tabulação obrigatória");
        }
        if (!tabulation.isActive()) {
            throw new ConflictException("interaction.tabulation_inactive", "Tabulação desativada");
        }
        return tabulation;
    }

    private static void requireFlagData(Tabulation t, RecordInteractionCommand cmd) {
        if (t.isPromise()) {
            Guard.require(cmd.promise() != null && cmd.promise().amount() != null && cmd.promise().dueDate() != null,
                    "interaction.promise_required", "A tabulação %s exige valor e data da promessa".formatted(t.getCode()));
        } else {
            Guard.require(cmd.promise() == null, "interaction.promise_not_allowed",
                    "A tabulação %s não registra promessa".formatted(t.getCode()));
        }
        if (t.isCallback()) {
            Guard.require(cmd.callback() != null && cmd.callback().dueAt() != null, "interaction.callback_required",
                    "A tabulação %s exige data e hora do retorno".formatted(t.getCode()));
        } else {
            Guard.require(cmd.callback() == null, "interaction.callback_not_allowed",
                    "A tabulação %s não agenda retorno".formatted(t.getCode()));
        }
    }

    /** Estágio de tratamento corrente (o guardado, durante uma retenção). */
    private static CaseStatus stage(CollectionCase c) {
        return c.getStatus().isHold() ? (c.getResumeStatus() == null ? CaseStatus.OPEN : c.getResumeStatus()) : c.getStatus();
    }

    private static UUID teamOf(Set<UUID> queueTeams, Set<UUID> userTeams) {
        return new java.util.TreeSet<>(userTeams).stream().filter(queueTeams::contains).findFirst()
                .orElse(single(userTeams));
    }

    private static UUID single(Set<UUID> userTeams) {
        return userTeams.size() == 1 ? userTeams.iterator().next() : null;
    }
}
