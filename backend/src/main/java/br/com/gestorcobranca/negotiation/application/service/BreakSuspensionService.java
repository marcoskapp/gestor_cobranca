package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.BreakSuspensionProperties;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementBreakSuspensionUseCases;
import br.com.gestorcobranca.negotiation.application.port.spi.PaymentReviewHolds;
import br.com.gestorcobranca.negotiation.application.port.spi.PaymentReviewHolds.PaymentHold;
import br.com.gestorcobranca.negotiation.application.port.spi.PaymentReviewHolds.ReviewOutcome;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementInstallment;
import br.com.gestorcobranca.negotiation.domain.model.agreement.BreakSuspension;
import br.com.gestorcobranca.negotiation.domain.model.agreement.BreakSuspensionEndReason;
import br.com.gestorcobranca.negotiation.domain.model.agreement.BreakSuspensionStatus;
import br.com.gestorcobranca.negotiation.domain.repository.AgreementRepository;
import br.com.gestorcobranca.negotiation.domain.repository.BreakSuspensionRepository;
import br.com.gestorcobranca.negotiation.domain.service.BreakHoldRules;
import br.com.gestorcobranca.negotiation.domain.service.BreakHoldRules.Outcome;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Suspensão da quebra enquanto há pagamento externo em revisão (decisões 540–549). Sempre com o acordo
 * bloqueado: o job diário avalia os acordos que atingiram a regra ({@link #evaluate}) e encerra as
 * suspensões cuja situação mudou ({@link #reconcileLocked}); o payment chama {@link #reconcile} na mesma
 * transação da aprovação/recusa. A situação dos pagamentos vem da fonte ({@link PaymentReviewHolds},
 * implementada pelo payment — ADR-0010), então começar e encerrar são idempotentes e consistentes.
 */
@Service
@Transactional
class BreakSuspensionService implements AgreementBreakSuspensionUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(BreakSuspensionService.class);

    private final AgreementRepository agreements;
    private final BreakSuspensionRepository suspensions;
    private final PaymentReviewHolds holds;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final NegotiationMetrics metrics;
    private final AccessScope accessScope;
    private final BreakSuspensionProperties properties;
    private final Clock clock;

    BreakSuspensionService(AgreementRepository agreements, BreakSuspensionRepository suspensions, PaymentReviewHolds holds,
                           AuditTrail auditTrail, BusinessEventLogger businessEvents, NegotiationMetrics metrics,
                           AccessScope accessScope, BreakSuspensionProperties properties, Clock clock) {
        this.agreements = agreements;
        this.suspensions = suspensions;
        this.holds = holds;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.accessScope = accessScope;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void reconcile(UUID agreementId) {
        if (agreementId == null) {
            return;
        }
        agreements.findByIdForUpdate(agreementId)
                .ifPresent(a -> reconcileLocked(a, LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)), Instant.now(clock)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BreakSuspensionView> latest(Collection<UUID> agreementIds) {
        accessScope.requirePermission(Permission.AGREEMENT_VIEW);
        Set<UUID> ids = agreementIds == null ? Set.of() : agreementIds.stream().filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Guard.require(ids.size() <= MAX_AGREEMENTS, "agreement.break_suspension_too_many",
                "Consulte até " + MAX_AGREEMENTS + " acordos por vez");
        return suspensions.findLatestByAgreements(ids).stream()
                .filter(s -> accessScope.canAccessCreditor(s.getCreditorId()))
                .map(BreakSuspensionService::view).toList();
    }

    @Override
    public BreakSuspensionSettings settings() {
        return new BreakSuspensionSettings(properties.maxDays(), properties.graceDays());
    }

    /** Acordos com suspensão em vigor (lote do job), ids após {@code afterId}. */
    List<UUID> activeAgreementIds(UUID afterId, int limit) {
        return suspensions.findActiveAgreementIds(afterId, limit);
    }

    /**
     * Encerra a suspensão em vigor se a revisão terminou, o prazo venceu ou o acordo foi encerrado.
     * Chame com o acordo bloqueado. Devolve a suspensão que continua em vigor (vazio se não há).
     */
    Optional<BreakSuspension> reconcileLocked(Agreement a, LocalDate today, Instant now) {
        Optional<BreakSuspension> found = suspensions.findActive(a.getId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        BreakSuspension s = found.get();
        PaymentHold hold = holds.holdFor(a.getId(), s.getStartedAt());
        boolean anyApproved = hold.reviewed().stream().anyMatch(ReviewOutcome::approved);
        BreakSuspensionEndReason reason = BreakHoldRules.reconcile(a.getStatus().isActive(), !hold.pending().isEmpty(),
                anyApproved, s.deadlinePassed(today));
        if (reason == null) {
            return found;
        }
        switch (reason) {
            case APPROVED, REJECTED -> s.endByReview(reason == BreakSuspensionEndReason.APPROVED,
                    lastReviewed(hold, reason == BreakSuspensionEndReason.APPROVED), today, properties.graceDays(), now);
            case DEADLINE -> s.endByDeadline(now);
            case AGREEMENT_ENDED -> s.endByAgreementEnded(now);
        }
        suspensions.save(s);
        ended(a, s, hold.pending().size(), today);
        return Optional.empty();
    }

    /**
     * Acordo bloqueado que atingiu a regra de quebra no dia: decide se quebra agora, se espera (suspensão,
     * carência, pagamento em trânsito) ou se começa uma suspensão.
     */
    Outcome evaluate(Agreement a, LocalDate today, Instant now) {
        boolean active = reconcileLocked(a, today, now).isPresent();
        Outcome outcome;
        if (active) {
            outcome = Outcome.HOLD_SUSPENDED;
        } else {
            PaymentHold hold = holds.holdFor(a.getId(), null);
            // alocação já baixada aqui, mas cujo retorno ao payment ainda não chegou, não está em trânsito
            boolean inTransit = hold.inTransit().stream().anyMatch(id -> agreements.findPayment(a.getId(), id).isEmpty());
            boolean inGrace = suspensions.findLatest(a.getId()).map(s -> s.inGrace(today)).orElse(false);
            Optional<AgreementInstallment> trigger = a.breakTriggerInstallment(today);
            boolean triggerBlocked = trigger.map(t -> suspensions.existsBlockingEnd(t.getId())).orElse(true);
            outcome = BreakHoldRules.decide(false, !hold.pending().isEmpty(), inTransit, inGrace, triggerBlocked);
            if (outcome == Outcome.SUSPEND) {
                start(a, trigger.orElseThrow(), hold, today, now);
            } else if (outcome == Outcome.BREAK_SUSPENSION_USED) {
                LOG.atInfo().addKeyValue("agreementId", a.getId()).addKeyValue("pendingPayments", hold.pending().size())
                        .log("Quebra sem nova suspensão: a parcela já teve suspensão encerrada por recusa ou prazo");
            }
        }
        metrics.breakEvaluated(outcome.name());
        return outcome;
    }

    private void start(Agreement a, AgreementInstallment trigger, PaymentHold hold, LocalDate today, Instant now) {
        Money pendingAmount = Money.of(hold.pendingAmount());
        BreakSuspension s = suspensions.save(new BreakSuspension(a, trigger, hold.pending().size(), pendingAmount, today,
                properties.maxDays(), now));
        Instant oldest = hold.pending().stream().map(PaymentReviewHolds.PendingReview::informedAt)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(now);
        auditTrail.record(AuditEntry.of("agreement.break_suspended", AgreementService.RESOURCE, a.getId())
                .creditor(a.getCreditorId()).created("breakSuspension", BreakSuspensionStatus.ACTIVE)
                .created("suspendedUntil", s.getSuspendedUntil()).created("pendingPayments", s.getPendingPayments())
                .created("pendingAmount", pendingAmount.amount())
                .meta("suspensionId", s.getId()).meta("triggerInstallment", trigger.getNumber())
                .meta("paymentIds", hold.pending().stream().map(PaymentReviewHolds.PendingReview::paymentId).toList()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.AGREEMENT_BREAK_SUSPENDED).creditor(a.getCreditorId())
                .portfolio(a.getPortfolioId()).debtor(a.getDebtorId()).collectionCase(a.getCaseId())
                .reference(AgreementService.RESOURCE, a.getId()).channel(a.getChannel()).amount(pendingAmount)
                .attr("suspensionId", s.getId().toString()).attr("pendingPayments", s.getPendingPayments())
                .attr("suspendedUntil", s.getSuspendedUntil()).attr("maxDays", properties.maxDays())
                .attr("overdueInstallments", a.overdueBeyondTolerance(today))
                .attr("triggerInstallmentNumber", trigger.getNumber())
                .attr("daysOverdue", ChronoUnit.DAYS.between(trigger.getDueDate(), today))
                .attr("oldestPendingHours", Duration.between(oldest, now).toHours())
                .attr("remainingAmount", a.remaining().amount())
                .attr("origin", a.getOrigin()).attr("actorType", a.getCloserType()));
        metrics.breakSuspensionStarted();
        LOG.atInfo().addKeyValue("agreementId", a.getId()).addKeyValue("suspensionId", s.getId())
                .addKeyValue("pendingPayments", s.getPendingPayments()).addKeyValue("suspendedUntil", s.getSuspendedUntil())
                .log("Quebra do acordo suspensa por pagamento em revisão");
    }

    private void ended(Agreement a, BreakSuspension s, int stillPending, LocalDate today) {
        long days = s.durationDays(today);
        auditTrail.record(AuditEntry.of("agreement.break_suspension_ended", AgreementService.RESOURCE, a.getId())
                .creditor(a.getCreditorId()).change("breakSuspension", BreakSuspensionStatus.ACTIVE, s.getStatus())
                .meta("suspensionId", s.getId()).meta("reason", s.getEndReason()).meta("graceUntil", s.getGraceUntil())
                .meta("reviewedPaymentId", s.getReviewedPaymentId()).meta("pendingPayments", stillPending));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.AGREEMENT_BREAK_SUSPENSION_ENDED).creditor(a.getCreditorId())
                .portfolio(a.getPortfolioId()).debtor(a.getDebtorId()).collectionCase(a.getCaseId())
                .reference(AgreementService.RESOURCE, a.getId()).channel(a.getChannel()).amount(s.getPendingAmount())
                .attr("suspensionId", s.getId().toString()).attr("reason", s.getEndReason())
                .attr("durationDays", days).attr("suspendedUntil", s.getSuspendedUntil())
                .attr("graceUntil", s.getGraceUntil()).attr("stillPendingPayments", stillPending)
                .attr("agreementStatus", a.getStatus()).attr("origin", a.getOrigin()));
        metrics.breakSuspensionEnded(s.getEndReason().name(), days);
        if (s.getEndReason() == BreakSuspensionEndReason.DEADLINE) {
            LOG.atWarn().addKeyValue("agreementId", a.getId()).addKeyValue("suspensionId", s.getId())
                    .addKeyValue("pendingPayments", stillPending).addKeyValue("suspendedUntil", s.getSuspendedUntil())
                    .log("Prazo máximo de suspensão da quebra vencido com pagamento ainda em revisão");
        } else {
            LOG.atInfo().addKeyValue("agreementId", a.getId()).addKeyValue("suspensionId", s.getId())
                    .addKeyValue("reason", s.getEndReason()).log("Suspensão da quebra encerrada");
        }
    }

    /** O revisado mais recente do resultado (aprovado ou recusado) — vai para a trilha. */
    private static UUID lastReviewed(PaymentHold hold, boolean approved) {
        return hold.reviewed().stream().filter(r -> r.approved() == approved)
                .max(Comparator.comparing(ReviewOutcome::reviewedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(ReviewOutcome::paymentId).orElse(null);
    }

    static BreakSuspensionView view(BreakSuspension s) {
        return new BreakSuspensionView(s.getId(), s.getAgreementId(), s.getStatus().name(), s.getStartedOn(),
                s.getStartedAt(), s.getSuspendedUntil(), s.getPendingPayments(), s.getPendingAmount().amount(),
                s.getEndReason() == null ? null : s.getEndReason().name(), s.getEndedAt(), s.getGraceUntil());
    }
}
