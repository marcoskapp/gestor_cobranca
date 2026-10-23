package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.incentive.application.port.in.AttributionQueries;
import br.com.gestorcobranca.incentive.application.port.out.AgreementLock;
import br.com.gestorcobranca.incentive.application.port.out.FactStore;
import br.com.gestorcobranca.incentive.application.port.out.FactStore.FactKey;
import br.com.gestorcobranca.incentive.application.port.out.FactStore.Measures;
import br.com.gestorcobranca.incentive.application.service.PlanResolver.ResolvedPlan;
import br.com.gestorcobranca.incentive.domain.model.attribution.AgreementAttribution;
import br.com.gestorcobranca.incentive.domain.model.attribution.AgreementOrigin;
import br.com.gestorcobranca.incentive.domain.model.attribution.AttributionCredit;
import br.com.gestorcobranca.incentive.domain.model.attribution.AttributionParticipant;
import br.com.gestorcobranca.incentive.domain.model.attribution.BeneficiaryType;
import br.com.gestorcobranca.incentive.domain.model.attribution.ParticipantActorType;
import br.com.gestorcobranca.incentive.domain.model.attribution.ParticipantRole;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules;
import br.com.gestorcobranca.incentive.domain.model.plan.StandardPlans;
import br.com.gestorcobranca.incentive.domain.repository.AgreementAttributionRepository;
import br.com.gestorcobranca.incentive.domain.repository.PaymentFactRepository;
import br.com.gestorcobranca.incentive.domain.service.CommissionEngine;
import br.com.gestorcobranca.incentive.domain.service.CreditPolicy;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Percentage;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.negotiation.AgreementAttributionRecorded;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Registra a atribuição de crédito do acordo (§3) com o plano vigente na formalização, os fatos
 * de acordo do closer e a comissão prevista; em seguida lança os pagamentos que já tinham chegado.
 */
@Service
@Transactional
class AttributionService implements AttributionQueries {

    private static final Logger LOG = LoggerFactory.getLogger(AttributionService.class);

    private final AgreementAttributionRepository attributions;
    private final PaymentFactRepository payments;
    private final AgreementLock agreementLock;
    private final PlanResolver planResolver;
    private final Workforce workforce;
    private final FactStore facts;
    private final CommissionPostingService posting;
    private final BusinessEventLogger businessEvents;
    private final AccessScope accessScope;
    private final IncentiveMetrics metrics;

    AttributionService(AgreementAttributionRepository attributions, PaymentFactRepository payments,
                       AgreementLock agreementLock, PlanResolver planResolver, Workforce workforce, FactStore facts,
                       CommissionPostingService posting, BusinessEventLogger businessEvents, AccessScope accessScope,
                       IncentiveMetrics metrics) {
        this.attributions = attributions;
        this.payments = payments;
        this.agreementLock = agreementLock;
        this.planResolver = planResolver;
        this.workforce = workforce;
        this.facts = facts;
        this.posting = posting;
        this.businessEvents = businessEvents;
        this.accessScope = accessScope;
        this.metrics = metrics;
    }

    void record(AgreementAttributionRecorded e) {
        metrics.factConsumed("attribution");
        agreementLock.lock(e.agreementId());
        if (attributions.existsByAgreementId(e.agreementId())) {
            LOG.atInfo().addKeyValue("agreementId", e.agreementId()).log("Atribuição do acordo já registrada; ignorando");
            return;
        }
        AgreementOrigin origin = AgreementOrigin.valueOf(e.origin());
        List<AttributionParticipant> participants = e.participants().stream()
                .map(p -> new AttributionParticipant(ParticipantActorType.valueOf(p.actorType()), p.actorId(),
                        ParticipantRole.valueOf(p.role())))
                .toList();
        Instant formalizedAt = e.formalizedAt() == null ? e.occurredAt() : e.formalizedAt();
        LocalDate day = FactIngestionService.day(formalizedAt);

        AgreementAttribution.Facts attributionFacts = new AgreementAttribution.Facts(e.eventId(), e.agreementId(), e.caseId(),
                e.debtorId(), e.creditorId(), e.portfolioId(), e.teamId(), origin, e.channel(), e.concessionLevel(),
                e.envelopeUsagePercent() == null ? null : Percentage.of(e.envelopeUsagePercent()), e.daysPastDue(),
                Money.ofNullable(e.agreementAmount()), optional(e.updatedBalance()), optional(e.discountAmount()),
                optional(e.downPaymentAmount()), e.replacedAgreementId(), formalizedAt, participants);

        Optional<ResolvedPlan> primary = planResolver.primary(day);
        Optional<UUID> influencer = Optional.empty();
        if (origin.creditsInfluencer() && e.caseId() != null) {
            int window = primary.map(p -> p.rules().attribution().influenceWindowDays())
                    .orElse(StandardPlans.padraoMercado().attribution().influenceWindowDays());
            influencer = facts.lastEffectiveContact(e.caseId(), formalizedAt.minus(Duration.ofDays(window)), formalizedAt);
        }
        Optional<UUID> individual = origin.creditsCloser() ? closer(participants)
                : origin.creditsInfluencer() ? influencer.or(() -> influencerFromEvent(participants)) : Optional.empty();
        Optional<ResolvedPlan> plan = individual.flatMap(workforce::member).flatMap(m -> planResolver.forMember(m, day))
                .or(() -> primary);
        PlanRules.AttributionRules rules = plan.map(p -> p.rules().attribution())
                .orElse(StandardPlans.padraoMercado().attribution());

        Optional<AgreementAttribution> replaced = e.replacedAgreementId() == null ? Optional.empty()
                : attributions.findByAgreementId(e.replacedAgreementId());
        Money incremental = CreditPolicy.incrementalAmount(attributionFacts.agreementAmount(),
                replaced.map(AgreementAttribution::getAgreementAmount).orElse(null),
                replaced.map(r -> Money.of(payments.sumCommissionableByAgreement(r.getAgreementId()))).orElse(null));
        List<AttributionCredit> credits = CreditPolicy.resolve(origin, participants, e.teamId(), rules, influencer,
                replaced.flatMap(AgreementAttribution::humanCloser));

        Money forecast = forecast(plan, attributionFacts, incremental, credits);
        AgreementAttribution attribution = attributions.save(new AgreementAttribution(attributionFacts, incremental,
                plan.map(ResolvedPlan::id).orElse(null), credits, forecast));
        facts.agreementFormalized(e.agreementId(), formalizedAt, e.agreementAmount(), e.replacedAgreementId());
        recordAgreementFacts(attribution, day);

        businessEvents.log(BusinessEvent.of(BusinessEventTypes.ATTRIBUTION_RECORDED)
                .creditor(e.creditorId()).portfolio(e.portfolioId()).debtor(e.debtorId()).collectionCase(e.caseId())
                .reference("agreement", e.agreementId()).channel(attribution.getChannel())
                .amount(attribution.getAgreementAmount())
                .attr("origin", origin).attr("delayBucket", attribution.getDelayBucket())
                .attr("renegotiation", attribution.isRenegotiation())
                .attr("incrementalAmount", incremental.amount())
                .attr("individualCredits", credits.stream().filter(c -> c.getBeneficiaryType() == BeneficiaryType.USER).count())
                .attr("teamPool", e.teamId() != null)
                .attr("influencerSource", credits.stream().filter(c -> c.getRole() == ParticipantRole.INFLUENCER)
                        .map(AttributionCredit::getReason).findFirst().orElse(null))
                .attr("planId", attribution.getPlanId())
                .attr("concessionLevel", e.concessionLevel())
                .attr("envelopeUsage", e.envelopeUsagePercent()));
        if (forecast != null) {
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.COMMISSION_FORECAST)
                    .creditor(e.creditorId()).portfolio(e.portfolioId()).reference("agreement", e.agreementId())
                    .channel(attribution.getChannel())
                    .attr("origin", origin).attr("forecastBand", CommissionBands.of(forecast))
                    .attr("delayBucket", attribution.getDelayBucket()));
        }
        posting.postPending(attribution);
    }

    /** Comissão prevista (informativa): incremental × fração de crédito × taxa de referência do segmento. */
    private static Money forecast(Optional<ResolvedPlan> plan, AgreementAttribution.Facts f, Money incremental,
                                  List<AttributionCredit> credits) {
        Optional<AttributionCredit> individual = credits.stream()
                .filter(c -> c.getBeneficiaryType() == BeneficiaryType.USER).findFirst();
        if (plan.isEmpty() || individual.isEmpty()) {
            return null;
        }
        BigDecimal rate = CommissionEngine.forecastRatePercent(plan.get().rules(),
                new br.com.gestorcobranca.incentive.domain.model.Segment(f.creditorId(), f.portfolioId(),
                        br.com.gestorcobranca.incentive.domain.model.DelayBucket.of(f.daysPastDue())));
        return incremental.times(individual.get().effectiveFraction()
                .multiply(rate.divide(new BigDecimal("100"), 10, RoundingMode.HALF_EVEN)));
    }

    /** Fatos de acordo do closer humano no dia da formalização (§2.2–§2.3). */
    private void recordAgreementFacts(AgreementAttribution a, LocalDate day) {
        a.humanCloser().ifPresent(closer -> {
            facts.increment(new FactKey(day, closer, a.getCreditorId(), a.getPortfolioId(), a.getDelayBucket(), a.getChannel()),
                    new Measures(0, 0, 0, 0, 1, a.getIncrementalAmount().amount(), a.getAgreementAmount().amount(),
                            amount(a.getUpdatedBalance()), amount(a.getDiscountAmount()), amount(a.getDownPaymentAmount()),
                            a.getEnvelopeUsage() == null ? null : a.getEnvelopeUsage().value(),
                            a.getEnvelopeUsage() == null ? 0 : 1,
                            a.getConcessionLevel() == null ? 0 : a.getConcessionLevel(),
                            a.getConcessionLevel() == null ? 0 : 1, 0, null, null));
            facts.firstInstallmentPaidAt(a.getAgreementId()).ifPresent(paidAt -> facts.increment(
                    new FactKey(FactIngestionService.day(paidAt), closer, a.getCreditorId(), a.getPortfolioId(),
                            a.getDelayBucket(), a.getChannel()), Measures.firstInstallment()));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public AttributionView byAgreement(UUID agreementId) {
        AgreementAttribution a = attributions.findByAgreementId(agreementId)
                .orElseThrow(() -> ResourceNotFoundException.of("attribution", agreementId));
        accessScope.requireVisible("attribution", agreementId, a.getCreditorId());
        return new AttributionView(a.getId(), a.getAgreementId(), a.getCaseId(), a.getCreditorId(), a.getPortfolioId(),
                a.getTeamId(), a.getOrigin().name(), a.getChannel(), a.getConcessionLevel(),
                a.getEnvelopeUsage() == null ? null : a.getEnvelopeUsage().value(), a.getDelayBucket().name(),
                a.getAgreementAmount().amount(), a.getIncrementalAmount().amount(), a.getReplacedAgreementId(),
                a.getFormalizedAt(), a.getPlanId(),
                a.getParticipants().stream().map(p -> new ParticipantView(p.getActorType().name(), p.getActorId(),
                        p.getRole().name())).toList(),
                a.getCredits().stream().map(c -> new CreditView(c.getBeneficiaryType().name(), c.getBeneficiaryId(),
                        c.getRole() == null ? null : c.getRole().name(), c.getWeight().value(),
                        c.getRenegotiationFactor().value(), c.getReason())).toList());
    }

    private static Optional<UUID> closer(List<AttributionParticipant> participants) {
        return participants.stream().filter(p -> p.getRole() == ParticipantRole.CLOSER && p.isHuman())
                .map(AttributionParticipant::getActorId).findFirst();
    }

    private static Optional<UUID> influencerFromEvent(List<AttributionParticipant> participants) {
        return participants.stream().filter(p -> p.getRole() == ParticipantRole.INFLUENCER && p.isHuman())
                .map(AttributionParticipant::getActorId).findFirst();
    }

    private static Money optional(BigDecimal value) {
        return value == null ? null : Money.of(value);
    }

    private static BigDecimal amount(Money money) {
        return money == null ? null : money.amount();
    }
}
