package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.domain.model.AssignmentMode;
import br.com.gestorcobranca.collection.domain.model.CaseAssignment;
import br.com.gestorcobranca.collection.domain.model.CaseStatus;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.EligibilityReason;
import br.com.gestorcobranca.collection.domain.model.OwnerType;
import br.com.gestorcobranca.collection.domain.repository.CaseAssignmentRepository;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.collection.domain.service.OperationBands;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.collection.CaseAssignmentChanged;
import br.com.gestorcobranca.shared.events.collection.CaseBalanceChanged;
import br.com.gestorcobranca.shared.events.collection.CaseStatusChanged;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Persistência das mudanças do caso com os efeitos obrigatórios, na transação do caso de uso:
 * <ul>
 *   <li>status ou elegibilidade mudou → {@link CaseStatusChanged} (com responsável, equipe e saldo
 *       elegível), auditoria e evento de negócio ({@code case.status_changed}, {@code case.closed},
 *       {@code case.eligibility_changed});</li>
 *   <li>dono do atendimento mudou → encerra o período vigente do histórico e abre outro; se o
 *       responsável humano ou a equipe mudou, {@link CaseAssignmentChanged}, auditoria e
 *       {@code case.assigned};</li>
 *   <li>só o saldo elegível atribuível mudou (pagamento, carga, baixa) → {@link CaseBalanceChanged}
 *       (nos demais casos o saldo já segue nos eventos acima).</li>
 * </ul>
 * Uso: capture {@link #snapshot(CollectionCase)} antes de alterar o caso e chame
 * {@link #commit} depois.
 */
@Component
class CaseRecorder {

    private final CollectionCaseRepository cases;
    private final CaseAssignmentRepository assignments;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final CurrentActor currentActor;
    private final CollectionMetrics metrics;
    private final Clock clock;

    CaseRecorder(CollectionCaseRepository cases, CaseAssignmentRepository assignments, AuditTrail auditTrail,
                 BusinessEventLogger businessEvents, ApplicationEventPublisher events, CurrentActor currentActor,
                 CollectionMetrics metrics, Clock clock) {
        this.cases = cases;
        this.assignments = assignments;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.events = events;
        this.currentActor = currentActor;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Estado relevante do caso antes de uma alteração. */
    record Snapshot(CaseStatus status, boolean eligible, Set<EligibilityReason> reasons, OwnerType ownerType,
                    UUID userId, UUID teamId, UUID queueId, Money assignableBalance) {
    }

    static Snapshot snapshot(CollectionCase c) {
        return new Snapshot(c.getStatus(), c.isEligible(), c.eligibility().reasons(), c.getOwnerType(),
                c.getAssigneeUserId(), c.getAssigneeTeamId(), c.getQueueId(), c.assignableBalance());
    }

    /** Caso recém-criado (já com posição, elegibilidade e fila definidas). */
    CollectionCase opened(CollectionCase c, String trigger) {
        CollectionCase saved = cases.save(c);
        Instant now = Instant.now(clock);
        assignments.save(new CaseAssignment(saved, AssignmentMode.OPENED, null, null, now));
        LocalDate today = CollectionViews.today(clock);
        auditTrail.record(AuditEntry.of("collection_case.opened", "collection_case", saved.getId())
                .creditor(saved.getCreditorId())
                .created("debtorId", saved.getDebtorId())
                .created("portfolioId", saved.getPortfolioId())
                .created("status", saved.getStatus())
                .created("openBalance", saved.getOpenBalance())
                .created("eligible", saved.isEligible())
                .created("queueId", saved.getQueueId())
                .meta("trigger", trigger));
        businessEvents.log(base(BusinessEventTypes.CASE_OPENED, saved, today)
                .amount(saved.getOpenBalance())
                .attr("trigger", trigger)
                .attr("openTitles", saved.getOpenTitles())
                .attr("eligible", saved.isEligible())
                .attr("ineligibleReasons", reasons(saved.eligibility().reasons()))
                .attr("queueId", saved.getQueueId()));
        publishStatus(saved, null, now);
        metrics.caseOpened(trigger);
        metrics.eligibilityEvaluated(saved.isEligible(), true);
        saved.eligibility().reasons().forEach(r -> metrics.ineligibleReason(r.name()));
        return saved;
    }

    /**
     * @param ownerMode origem da mudança de dono, se houver (nulo = {@code ROUTING})
     * @param reason    motivo informado (auditoria e histórico)
     * @param trigger   o que provocou a alteração (evento de negócio), ex.: {@code title_status}, {@code manual}
     */
    CollectionCase commit(CollectionCase c, Snapshot before, AssignmentMode ownerMode, String reason, String trigger) {
        CollectionCase saved = cases.save(c);
        Instant now = Instant.now(clock);
        LocalDate today = CollectionViews.today(clock);
        boolean statusChanged = before.status() != saved.getStatus();
        boolean eligibilityChanged = before.eligible() != saved.isEligible();
        if (statusChanged) {
            statusEffects(saved, before, reason, trigger, now, today);
        }
        // o encerramento já é o fato (case.closed); a inelegibilidade decorrente não gera evento próprio
        if (saved.getStatus().isActive()
                && (eligibilityChanged || !before.reasons().equals(saved.eligibility().reasons()))) {
            eligibilityEffects(saved, before, trigger, today, eligibilityChanged);
        }
        boolean ownerChanged = before.ownerType() != saved.getOwnerType()
                || !Objects.equals(before.userId(), saved.getAssigneeUserId())
                || !Objects.equals(before.teamId(), saved.getAssigneeTeamId())
                || !Objects.equals(before.queueId(), saved.getQueueId());
        boolean assignmentPublished = ownerChanged
                && ownerEffects(saved, before, ownerMode == null ? AssignmentMode.ROUTING : ownerMode, reason, now, today);
        if (statusChanged || eligibilityChanged) {
            publishStatus(saved, before.status(), now);
        } else if (!assignmentPublished && !before.assignableBalance().equals(saved.assignableBalance())) {
            // o saldo elegível atribuído é o denominador da taxa de recuperação do incentive: sem este
            // evento, pagamentos e cargas que não mudam status nem dono deixariam o saldo defasado
            events.publishEvent(new CaseBalanceChanged(Ids.newId(), currentActor.tenantId(), now, saved.getId(),
                    saved.getDebtorId(), saved.getCreditorId(), saved.getPortfolioId(), saved.getAssigneeUserId(),
                    saved.getAssigneeTeamId(), saved.isEligible(), before.assignableBalance().amount(),
                    saved.assignableBalance().amount()));
        }
        return saved;
    }

    private void statusEffects(CollectionCase c, Snapshot before, String reason, String trigger, Instant now,
                               LocalDate today) {
        auditTrail.record(AuditEntry.of(c.getStatus() == CaseStatus.CLOSED ? "collection_case.closed"
                        : "collection_case.status_changed", "collection_case", c.getId())
                .creditor(c.getCreditorId())
                .reason(reason)
                .change("status", before.status(), c.getStatus())
                .meta("closeReason", c.getCloseReason())
                .meta("trigger", trigger));
        if (c.getStatus() == CaseStatus.CLOSED) {
            businessEvents.log(base(BusinessEventTypes.CASE_CLOSED, c, today)
                    .amount(c.getOpenBalance())
                    .durationMs(Duration.between(c.getOpenedAt(), now).toMillis())
                    .attr("reason", c.getCloseReason())
                    .attr("from", before.status())
                    .attr("trigger", trigger)
                    .attr("durationDays", Duration.between(c.getOpenedAt(), now).toDays())
                    .attr("interactions", c.getInteractionCount())
                    .attr("effectiveContacts", c.getEffectiveContactCount())
                    .attr("assigneeId", before.userId()));
            metrics.caseClosed(c.getCloseReason().name());
        } else {
            businessEvents.log(base(BusinessEventTypes.CASE_STATUS_CHANGED, c, today)
                    .amount(c.getOpenBalance())
                    .attr("from", before.status())
                    .attr("to", c.getStatus())
                    .attr("trigger", trigger)
                    .attr("assigneeId", c.getAssigneeUserId()));
        }
        metrics.caseTransition(before.status().name(), c.getStatus().name());
    }

    private void eligibilityEffects(CollectionCase c, Snapshot before, String trigger, LocalDate today,
                                    boolean flagChanged) {
        auditTrail.record(AuditEntry.of("collection_case.eligibility_changed", "collection_case", c.getId())
                .creditor(c.getCreditorId())
                .change("eligible", before.eligible(), c.isEligible())
                .change("eligibilityReasons", reasons(before.reasons()), reasons(c.eligibility().reasons()))
                .meta("trigger", trigger));
        if (flagChanged) {
            businessEvents.log(base(BusinessEventTypes.CASE_ELIGIBILITY_CHANGED, c, today)
                    .amount(c.getEligibleBalance())
                    .attr("eligible", c.isEligible())
                    .attr("reasons", reasons(c.eligibility().reasons()))
                    .attr("previousReasons", reasons(before.reasons()))
                    .attr("trigger", trigger));
            c.eligibility().reasons().forEach(r -> metrics.ineligibleReason(r.name()));
        }
        metrics.eligibilityEvaluated(c.isEligible(), flagChanged);
    }

    /** @return true se publicou {@link CaseAssignmentChanged} (responsável humano ou equipe mudou) */
    private boolean ownerEffects(CollectionCase c, Snapshot before, AssignmentMode mode, String reason, Instant now,
                                 LocalDate today) {
        CaseAssignment previous = assignments.findCurrentByCaseId(c.getId()).orElse(null);
        if (previous != null) {
            previous.close(now);
            // o fim de vigência precisa chegar ao banco antes do novo período (índice de período vigente único)
            assignments.saveAndFlush(previous);
        }
        CaseAssignment opened = new CaseAssignment(c, mode, previous, reason, now);
        if (!c.getStatus().isActive()) {
            // encerramento: período de registro, sem duração (o caso fica sem dono vigente)
            opened.close(now);
        }
        assignments.save(opened);
        metrics.assignment(mode.name(), c.getOwnerType().name());
        boolean humanChanged = !Objects.equals(before.userId(), c.getAssigneeUserId())
                || !Objects.equals(before.teamId(), c.getAssigneeTeamId());
        if (!humanChanged) {
            return false;
        }
        auditTrail.record(AuditEntry.of("collection_case.assigned", "collection_case", c.getId())
                .creditor(c.getCreditorId())
                .reason(reason)
                .change("ownerType", before.ownerType(), c.getOwnerType())
                .change("assigneeUserId", before.userId(), c.getAssigneeUserId())
                .change("assigneeTeamId", before.teamId(), c.getAssigneeTeamId())
                .meta("mode", mode));
        businessEvents.log(base(BusinessEventTypes.CASE_ASSIGNED, c, today)
                .amount(c.assignableBalance())
                .attr("mode", mode)
                .attr("ownerType", c.getOwnerType())
                .attr("assigneeId", c.getAssigneeUserId())
                .attr("previousAssigneeId", before.userId())
                .attr("teamId", c.getAssigneeTeamId())
                .attr("queueId", c.getQueueId())
                .attr("eligible", c.isEligible()));
        events.publishEvent(new CaseAssignmentChanged(Ids.newId(), currentActor.tenantId(), now, c.getId(),
                c.getDebtorId(), c.getCreditorId(), c.getPortfolioId(), before.userId(), c.getAssigneeUserId(),
                c.getAssigneeTeamId(), c.assignableBalance().amount()));
        return true;
    }

    private void publishStatus(CollectionCase c, CaseStatus previous, Instant now) {
        events.publishEvent(new CaseStatusChanged(Ids.newId(), currentActor.tenantId(), now, c.getId(), c.getDebtorId(),
                c.getCreditorId(), c.getPortfolioId(), previous == null ? null : previous.name(), c.getStatus().name(),
                c.isEligible(), c.getAssigneeUserId(), c.getAssigneeTeamId(), c.assignableBalance().amount()));
    }

    /** Dimensões comuns dos eventos de negócio do caso: credor, carteira, devedor, caso, faixas. */
    static BusinessEvent base(String type, CollectionCase c, LocalDate today) {
        return BusinessEvent.of(type)
                .creditor(c.getCreditorId()).portfolio(c.getPortfolioId()).debtor(c.getDebtorId())
                .collectionCase(c.getId())
                .attr("daysOverdue", c.daysOverdue(today))
                .attr("overdueBand", OperationBands.overdueBand(c.daysOverdue(today)))
                .attr("valueBand", OperationBands.valueBand(c.getOpenBalance()));
    }

    static String reasons(Set<EligibilityReason> reasons) {
        return reasons.isEmpty() ? null : reasons.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }
}
