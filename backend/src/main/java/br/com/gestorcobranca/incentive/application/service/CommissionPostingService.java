package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries.WorkforceMember;
import br.com.gestorcobranca.incentive.application.IncentiveProperties;
import br.com.gestorcobranca.incentive.application.port.out.AgreementLock;
import br.com.gestorcobranca.incentive.application.port.out.FactStore;
import br.com.gestorcobranca.incentive.application.port.out.FactStore.FactKey;
import br.com.gestorcobranca.incentive.application.port.out.FactStore.Measures;
import br.com.gestorcobranca.incentive.application.service.PlanResolver.ResolvedPlan;
import br.com.gestorcobranca.incentive.domain.model.attribution.AgreementAttribution;
import br.com.gestorcobranca.incentive.domain.model.attribution.AttributionCredit;
import br.com.gestorcobranca.incentive.domain.model.attribution.BeneficiaryType;
import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionLedgerEntry;
import br.com.gestorcobranca.incentive.domain.model.ledger.EntryKind;
import br.com.gestorcobranca.incentive.domain.model.ledger.EntryStatus;
import br.com.gestorcobranca.incentive.domain.model.ledger.PaymentFact;
import br.com.gestorcobranca.incentive.domain.model.ledger.PaymentReversalFact;
import br.com.gestorcobranca.incentive.domain.model.plan.CreditModel;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules;
import br.com.gestorcobranca.incentive.domain.repository.AgreementAttributionRepository;
import br.com.gestorcobranca.incentive.domain.repository.LedgerRepository;
import br.com.gestorcobranca.incentive.domain.repository.PaymentFactRepository;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Percentage;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fato comissionável → razão de comissão (§5.3–§5.4). Pagamento confirmado gera um crédito
 * {@code PENDING} por beneficiário da atribuição, liberado após a carência em dias úteis; estorno
 * gera compensação proporcional: dentro da carência anula o crédito no mesmo período; depois do
 * fechamento só tem efeito se o plano permitir estorno de comissão (senão fica {@code WAIVED}).
 */
@Service
@Transactional
class CommissionPostingService {

    private static final Logger LOG = LoggerFactory.getLogger(CommissionPostingService.class);

    static final String NOTE_TERMINATED = "TERMINATED";
    static final String NOTE_PORTFOLIO = "PORTFOLIO_MODEL";

    private final PaymentFactRepository payments;
    private final AgreementAttributionRepository attributions;
    private final LedgerRepository ledger;
    private final AgreementLock agreementLock;
    private final PlanResolver planResolver;
    private final Workforce workforce;
    private final HolidayCalendar calendar;
    private final PeriodCalendar periods;
    private final FactStore facts;
    private final BusinessEventLogger businessEvents;
    private final IncentiveMetrics metrics;
    private final IncentiveProperties properties;
    private final Clock clock;

    CommissionPostingService(PaymentFactRepository payments, AgreementAttributionRepository attributions,
                             LedgerRepository ledger, AgreementLock agreementLock, PlanResolver planResolver,
                             Workforce workforce, HolidayCalendar calendar, PeriodCalendar periods, FactStore facts,
                             BusinessEventLogger businessEvents, IncentiveMetrics metrics, IncentiveProperties properties,
                             Clock clock) {
        this.payments = payments;
        this.attributions = attributions;
        this.ledger = ledger;
        this.agreementLock = agreementLock;
        this.planResolver = planResolver;
        this.workforce = workforce;
        this.calendar = calendar;
        this.periods = periods;
        this.facts = facts;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /** Base comissionável: alocações a parcelas do acordo (sem alocação detalhada, o valor do pagamento). */
    void paymentConfirmed(PaymentConfirmed e) {
        metrics.factConsumed("payment_confirmed");
        if (e.agreementId() == null) {
            return;
        }
        BigDecimal commissionable = e.allocations().isEmpty() ? e.amount()
                : e.allocations().stream().filter(a -> a.agreementInstallmentId() != null)
                .map(PaymentConfirmed.Allocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (commissionable == null || commissionable.signum() <= 0 || e.amount() == null || e.amount().signum() <= 0) {
            return;
        }
        agreementLock.lock(e.agreementId());
        if (payments.existsByPaymentId(e.paymentId())) {
            return;
        }
        Money total = Money.of(e.amount());
        payments.save(new PaymentFact(e.eventId(), e.paymentId(), e.agreementId(), e.creditorId(), e.portfolioId(), total,
                Money.of(commissionable).min(total), e.paidAt() == null ? e.occurredAt() : e.paidAt(), e.occurredAt()));
        Optional<AgreementAttribution> attribution = attributions.findByAgreementId(e.agreementId());
        if (attribution.isEmpty()) {
            metrics.paymentAwaitingAttribution();
            LOG.atInfo().addKeyValue("agreementId", e.agreementId()).addKeyValue("paymentId", e.paymentId())
                    .log("Pagamento recebido antes da atribuição do acordo; lançamento aguardando a atribuição");
            return;
        }
        postPending(attribution.get());
    }

    void paymentReversed(PaymentReversed e) {
        metrics.factConsumed("payment_reversed");
        if (e.amount() == null || e.amount().signum() <= 0) {
            return;
        }
        Optional<PaymentFact> payment = payments.findByPaymentId(e.paymentId());
        UUID agreementId = payment.map(PaymentFact::getAgreementId).orElse(e.agreementId());
        if (agreementId == null) {
            return;
        }
        agreementLock.lock(agreementId);
        if (payments.existsReversal(e.reversalId())) {
            return;
        }
        payments.saveReversal(new PaymentReversalFact(e.eventId(), e.reversalId(), e.paymentId(), agreementId,
                Money.of(e.amount()), e.reason(), e.occurredAt()));
        payments.findByPaymentId(e.paymentId()).filter(PaymentFact::isPosted).ifPresent(this::postReversals);
    }

    /** Lança os pagamentos do acordo ainda sem lançamento (chamado com o lock do acordo já obtido). */
    void postPending(AgreementAttribution attribution) {
        for (PaymentFact payment : payments.findUnpostedByAgreement(attribution.getAgreementId())) {
            post(payment, attribution);
            postReversals(payment);
        }
    }

    /** Contingência: tenta lançar pagamentos antigos ainda pendentes (atribuição recebida por outra via). */
    int sweep(Instant before, int limit) {
        List<UUID> agreements = payments.findAgreementsWithUnpostedPaymentsBefore(before, limit);
        int posted = 0;
        for (UUID agreementId : agreements) {
            agreementLock.lock(agreementId);
            Optional<AgreementAttribution> attribution = attributions.findByAgreementId(agreementId);
            if (attribution.isPresent()) {
                postPending(attribution.get());
                posted++;
            }
        }
        return posted;
    }

    private void post(PaymentFact payment, AgreementAttribution attribution) {
        Instant now = Instant.now(clock);
        LocalDate paidOn = FactIngestionService.day(payment.getPaidAt());
        LocalDate confirmedOn = FactIngestionService.day(payment.getConfirmedAt());
        CommissionLedgerEntry.Source source = new CommissionLedgerEntry.Source(attribution.getId(),
                attribution.getAgreementId(), payment.getPaymentId(), attribution.segment(), attribution.getOrigin(),
                attribution.getChannel());
        for (AttributionCredit credit : attribution.getCredits()) {
            CommissionLedgerEntry entry = credit.getBeneficiaryType() == BeneficiaryType.TEAM
                    ? teamEntry(source, credit, payment, paidOn, confirmedOn)
                    : userEntry(source, credit, attribution, payment, paidOn, confirmedOn);
            ledger.save(entry);
            metrics.ledgerEntry(EntryKind.ACCRUAL.name(), entry.getBeneficiaryType().name());
            if (entry.getBeneficiaryType() == BeneficiaryType.USER) {
                facts.increment(new FactKey(paidOn, entry.getBeneficiaryId(), entry.getCreditorId(), entry.getPortfolioId(),
                        entry.getDelayBucket(), entry.getChannel()), Measures.recovery(entry.getCreditedAmount().amount(), null));
            }
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.COMMISSION_ENTRY_POSTED)
                    .creditor(entry.getCreditorId()).portfolio(entry.getPortfolioId())
                    .collectionCase(attribution.getCaseId()).reference("commission_entry", entry.getId())
                    .channel(entry.getChannel()).amount(entry.getBaseAmount())
                    .attr("agreementId", entry.getAgreementId()).attr("paymentId", entry.getPaymentId())
                    .attr("beneficiaryType", entry.getBeneficiaryType()).attr("beneficiaryId", entry.getBeneficiaryId())
                    .attr("role", entry.getRole()).attr("origin", entry.getOrigin())
                    .attr("creditWeight", entry.getCreditWeight().value())
                    .attr("renegotiationFactor", entry.getRenegotiationFactor().value())
                    .attr("creditNote", entry.getCreditNote()).attr("delayBucket", entry.getDelayBucket())
                    .attr("clearsOn", entry.getClearsOn().toString()));
        }
        payment.markPosted(now);
        payments.save(payment);
    }

    private CommissionLedgerEntry userEntry(CommissionLedgerEntry.Source source, AttributionCredit credit,
                                            AgreementAttribution attribution, PaymentFact payment, LocalDate paidOn,
                                            LocalDate confirmedOn) {
        UUID beneficiary = credit.getBeneficiaryId();
        String note = credit.getReason();
        Optional<WorkforceMember> member = workforce.member(beneficiary);
        Optional<ResolvedPlan> plan = member.flatMap(m -> planResolver.forMember(m, paidOn));
        if (plan.map(p -> p.rules().attribution().creditModel() == CreditModel.PORTFOLIO).orElse(false)
                && attribution.getCaseId() != null) {
            Optional<UUID> assignee = facts.assigneeAt(attribution.getCaseId(), payment.getPaidAt());
            if (assignee.isPresent() && !assignee.get().equals(beneficiary)) {
                beneficiary = assignee.get();
                note = NOTE_PORTFOLIO;
                member = workforce.member(beneficiary);
                plan = member.flatMap(m -> planResolver.forMember(m, paidOn)).or(() -> planResolver.primary(paidOn));
            }
        }
        Percentage weight = credit.getWeight();
        if (member.isPresent() && terminatedBeforePayment(member.get(), plan.map(ResolvedPlan::rules), paidOn)) {
            weight = Percentage.ZERO;
            note = NOTE_TERMINATED;
        }
        int grace = plan.map(p -> p.rules().clearing().graceBusinessDays()).orElse(properties.defaultGraceBusinessDays());
        return CommissionLedgerEntry.accrual(source, BeneficiaryType.USER, beneficiary, credit.getRole(),
                payment.getCommissionableAmount(), weight, credit.getRenegotiationFactor(), note,
                plan.map(ResolvedPlan::id).orElse(null), paidOn, confirmedOn, calendar.plusBusinessDays(confirmedOn, grace));
    }

    private CommissionLedgerEntry teamEntry(CommissionLedgerEntry.Source source, AttributionCredit credit,
                                            PaymentFact payment, LocalDate paidOn, LocalDate confirmedOn) {
        Optional<ResolvedPlan> plan = planResolver.primary(paidOn);
        int grace = plan.map(p -> p.rules().clearing().graceBusinessDays()).orElse(properties.defaultGraceBusinessDays());
        return CommissionLedgerEntry.accrual(source, BeneficiaryType.TEAM, credit.getBeneficiaryId(), null,
                payment.getCommissionableAmount(), credit.getWeight(), credit.getRenegotiationFactor(), credit.getReason(),
                plan.map(ResolvedPlan::id).orElse(null), paidOn, confirmedOn, calendar.plusBusinessDays(confirmedOn, grace));
    }

    /** Política de desligamento (§3.3): pagamento após o desligamento (+ carência, conforme o plano) não remunera. */
    private boolean terminatedBeforePayment(WorkforceMember member, Optional<PlanRules> rules, LocalDate paidOn) {
        if (member.disabledAt() == null) {
            return false;
        }
        LocalDate terminatedOn = FactIngestionService.day(member.disabledAt());
        PlanRules.AttributionRules attribution = rules.map(PlanRules::attribution).orElse(null);
        if (attribution == null) {
            return paidOn.isAfter(calendar.plusBusinessDays(terminatedOn, properties.defaultGraceBusinessDays()));
        }
        return switch (attribution.terminationPolicy()) {
            case ALWAYS_CREDIT -> false;
            case CREDIT_UNTIL_TERMINATION -> paidOn.isAfter(terminatedOn);
            case CREDIT_UNTIL_TERMINATION_PLUS_GRACE ->
                    paidOn.isAfter(calendar.plusBusinessDays(terminatedOn, attribution.terminationGraceDays()));
        };
    }

    /** Compensações proporcionais dos estornos ainda não lançados de um pagamento já lançado. */
    private void postReversals(PaymentFact payment) {
        List<PaymentReversalFact> reversals = payments.findReversalsByPayment(payment.getPaymentId());
        Money alreadyReversed = reversals.stream().filter(r -> r.getPostedAt() != null).map(PaymentReversalFact::getAmount)
                .reduce(Money.ZERO, Money::plus);
        List<CommissionLedgerEntry> entries = ledger.findByPayment(payment.getPaymentId());
        List<CommissionLedgerEntry> accruals = entries.stream().filter(e -> e.getKind() == EntryKind.ACCRUAL).toList();
        Instant now = Instant.now(clock);
        for (PaymentReversalFact reversal : reversals) {
            if (reversal.getPostedAt() != null) {
                continue;
            }
            Money remaining = payment.getTotalAmount().minus(alreadyReversed);
            Money applied = reversal.getAmount().min(remaining);
            if (applied.isPositive()) {
                BigDecimal fraction = applied.amount().divide(payment.getTotalAmount().amount(), 10, RoundingMode.HALF_EVEN);
                LocalDate reversedOn = FactIngestionService.day(reversal.getReversedAt());
                for (CommissionLedgerEntry accrual : accruals) {
                    compensate(accrual, fraction, reversal, reversedOn, now);
                }
                alreadyReversed = alreadyReversed.plus(applied);
            }
            reversal.markPosted(now);
            payments.saveReversal(reversal);
        }
    }

    private void compensate(CommissionLedgerEntry accrual, BigDecimal fraction, PaymentReversalFact reversal,
                            LocalDate reversedOn, Instant now) {
        CommissionLedgerEntry entry = CommissionLedgerEntry.reversalOf(accrual, fraction, reversal.getReversalId(), reversedOn);
        String effect;
        if (accrual.getStatus() == EntryStatus.PENDING) {
            effect = "WITHIN_GRACE";
        } else if (periods.isOpen(accrual.getPeriodStart())) {
            entry.clear(accrual.getPeriodStart(), now);
            effect = "OPEN_PERIOD";
        } else if (clawbackEnabled(accrual)) {
            entry.clear(periods.firstOpenFrom(reversedOn), now);
            effect = "CLAWBACK";
        } else {
            entry.waive();
            effect = "WAIVED";
        }
        ledger.save(entry);
        metrics.ledgerEntry(EntryKind.REVERSAL.name(), entry.getBeneficiaryType().name());
        if (entry.getBeneficiaryType() == BeneficiaryType.USER) {
            facts.increment(new FactKey(reversedOn, entry.getBeneficiaryId(), entry.getCreditorId(), entry.getPortfolioId(),
                    entry.getDelayBucket(), entry.getChannel()), Measures.recovery(null, entry.getCreditedAmount().abs().amount()));
        }
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.COMMISSION_ENTRY_REVERSED)
                .creditor(entry.getCreditorId()).portfolio(entry.getPortfolioId())
                .reference("commission_entry", entry.getId()).channel(entry.getChannel())
                .amount(entry.getBaseAmount().abs())
                .attr("agreementId", entry.getAgreementId()).attr("paymentId", entry.getPaymentId())
                .attr("reversalId", entry.getReversalId()).attr("reversesEntryId", accrual.getId())
                .attr("beneficiaryType", entry.getBeneficiaryType()).attr("beneficiaryId", entry.getBeneficiaryId())
                .attr("origin", entry.getOrigin()).attr("effect", effect).attr("status", entry.getStatus()));
    }

    private boolean clawbackEnabled(CommissionLedgerEntry accrual) {
        return planResolver.byId(accrual.getPlanId()).map(p -> p.rules().clearing().clawbackEnabled()).orElse(false);
    }
}
