package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.service.NegotiationGuard.Evaluation;
import br.com.gestorcobranca.negotiation.application.service.ProposalEvaluator.Result;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement.InstallmentChange;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement.ScheduleEntry;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement.StatusChange;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement.TitleParts;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementStatus;
import br.com.gestorcobranca.negotiation.domain.model.agreement.InstallmentStatus;
import br.com.gestorcobranca.negotiation.domain.model.agreement.LedgerPart;
import br.com.gestorcobranca.negotiation.domain.model.calculation.Breakdown;
import br.com.gestorcobranca.negotiation.domain.model.offer.PricedDebt.TitlePricing;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.BreakRule;
import br.com.gestorcobranca.negotiation.domain.model.proposal.Proposal;
import br.com.gestorcobranca.negotiation.domain.repository.AgreementRepository;
import br.com.gestorcobranca.negotiation.domain.service.AttributionRules;
import br.com.gestorcobranca.negotiation.domain.service.ProportionalSplit;
import br.com.gestorcobranca.negotiation.domain.service.ProposalTerms;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.negotiation.AgreementAttributionRecorded;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.negotiation.InstallmentStatusChanged;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Formalização e registro das mudanças de acordo: cria o agregado a partir da proposta revalidada,
 * substitui o acordo renegociado e publica, na mesma transação, {@code AgreementStatusChanged},
 * {@code InstallmentStatusChanged} e {@code AgreementAttributionRecorded}, com auditoria, eventos de
 * negócio ({@code agreement.*}, {@code installment.*}) e métricas.
 */
@Component
class AgreementRecorder {

    private final AgreementRepository agreements;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final CurrentActor currentActor;
    private final NegotiationMetrics metrics;
    private final Clock clock;

    AgreementRecorder(AgreementRepository agreements, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                      ApplicationEventPublisher events, CurrentActor currentActor, NegotiationMetrics metrics, Clock clock) {
        this.agreements = agreements;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.events = events;
        this.currentActor = currentActor;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * @param handoff sugestão do assistente que originou a proposta (nula se não houve handoff)
     */
    Agreement formalize(Proposal p, Result r, Actor closer, Proposal handoff) {
        Evaluation e = r.evaluation();
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        Agreement replaced = null;
        if (p.getReplacedAgreementId() != null) {
            replaced = agreements.findByIdForUpdate(p.getReplacedAgreementId())
                    .orElseThrow(() -> new ResourceNotFoundException(AgreementService.RESOURCE, p.getReplacedAgreementId()));
            Guard.requireState(replaced.getStatus().isRenegotiable(), "agreement.not_renegotiable",
                    "O acordo a renegociar não está mais em vigor nem quebrado");
        }
        boolean handoffMatch = handoff != null && handoff.getTermsFingerprint().equals(p.getTermsFingerprint());
        AttributionRules.Attribution attribution = AttributionRules.resolve(new AttributionRules.Facts(
                closer.type().name(), closer.id(), p.getActorType(), p.getActorId(), p.isCopilot(),
                handoff == null ? null : handoff.getActorId(), handoffMatch, p.wasApproved() ? p.getDecidedBy() : null));
        BreakRule breakRule = r.ctx().rules().breakRule();
        Money incremental = ProposalTerms.incrementalAmount(e.plan().total(),
                replaced == null ? null : replaced.getTotalAmount(), replaced == null ? null : replaced.getPaidAmount());
        List<ScheduleEntry> schedule = e.plan().schedule().stream()
                .map(s -> new ScheduleEntry(s.number(), s.dueDate(), s.amount())).toList();
        Agreement agreement = new Agreement(new Agreement.Terms(p.getId(), p.getSimulationId(), p.getCreditorId(),
                p.getDebtorId(), p.getPortfolioId(), p.getCaseId(), p.getTeamId(), p.getPolicyId(), p.getPolicyVersion(),
                p.getOverrideCode(), p.getReferenceDate(), p.getLevel(), p.getEnvelopeUsage().value(),
                p.getMaxDaysOverdue(), p.getAcceptance().getChannel(), attribution.origin(), p.getActorType(),
                p.getActorId(), closer.type().name(), closer.id(), p.wasApproved() ? p.getDecidedBy() : null,
                attribution.preNegotiatorId(), e.priced().gross().total(), e.priced().discount().total(),
                e.plan().negotiated(), e.plan().financingInterest(), e.plan().total(), e.plan().downPayment(),
                breakRule.overdueInstallments(), breakRule.toleranceDays(), p.getReplacedAgreementId(),
                p.getRenegotiationNumber(), incremental, p.getAcceptance().copy(), now), schedule,
                titleParts(e.priced().titles(), e.plan().financingInterest()));
        if (replaced != null) {
            Agreement.Ending ending = replaced.renegotiatedBy(agreement.getId(), today, now);
            agreements.saveAndFlush(replaced);
            statusChanged(replaced, ending.change(), now);
            Agreement old = replaced;
            ending.installmentChanges().forEach(c -> installmentChanged(old, c, now));
        }
        agreements.saveAndFlush(agreement);
        statusChanged(agreement, new StatusChange(null, AgreementStatus.FORMALIZED), now);
        events.publishEvent(new AgreementAttributionRecorded(Ids.newId(), currentActor.tenantId(), now, agreement.getId(),
                agreement.getCaseId(), agreement.getDebtorId(), agreement.getCreditorId(), agreement.getPortfolioId(),
                agreement.getTeamId(), attribution.origin().name(), agreement.getChannel(),
                attribution.participants().stream().map(x -> new AgreementAttributionRecorded.Participant(x.actorType(),
                        x.actorId(), x.role())).toList(), agreement.getConcessionLevel(),
                agreement.getEnvelopeUsage().value(), (int) Math.min(Integer.MAX_VALUE, agreement.getMaxDaysOverdue()),
                agreement.getTotalAmount().amount(), agreement.getUpdatedAmount().amount(),
                agreement.getDiscountAmount().amount(), agreement.getDownPayment().amount(),
                agreement.getReplacedAgreementId(), now));
        auditTrail.record(AuditEntry.of("agreement.formalized", AgreementService.RESOURCE, agreement.getId())
                .creditor(agreement.getCreditorId()).created("status", AgreementStatus.FORMALIZED)
                .created("totalAmount", agreement.getTotalAmount().amount()).created("installments", schedule.size())
                .created("origin", attribution.origin()).created("termSha256", agreement.getAcceptance().getTermSha256())
                .meta("proposalId", p.getId()).meta("closerType", closer.type())
                .meta("replacedAgreementId", agreement.getReplacedAgreementId()));
        return agreement;
    }

    /** Valores por título e componente do razão; os juros do parcelamento são rateados pelo líquido de cada título. */
    static List<TitleParts> titleParts(List<TitlePricing> titles, Money financingInterest) {
        List<Money> shares = ProportionalSplit.split(financingInterest, titles.stream().map(t -> t.net().total()).toList());
        java.util.ArrayList<TitleParts> parts = new java.util.ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            TitlePricing t = titles.get(i);
            Breakdown g = t.gross();
            Breakdown d = t.discount();
            Breakdown rec = t.recognized();
            Map<LedgerPart, Money> gross = new EnumMap<>(LedgerPart.class);
            Map<LedgerPart, Money> discount = new EnumMap<>(LedgerPart.class);
            Map<LedgerPart, Money> recognized = new EnumMap<>(LedgerPart.class);
            gross.put(LedgerPart.PRINCIPAL, g.principal());
            discount.put(LedgerPart.PRINCIPAL, d.principal());
            gross.put(LedgerPart.INTEREST, g.interest().plus(shares.get(i)));
            discount.put(LedgerPart.INTEREST, d.interest());
            recognized.put(LedgerPart.INTEREST, rec.interest());
            gross.put(LedgerPart.FINE, g.fine());
            discount.put(LedgerPart.FINE, d.fine());
            recognized.put(LedgerPart.FINE, rec.fine());
            gross.put(LedgerPart.FEES, g.fees().plus(g.correction()));
            discount.put(LedgerPart.FEES, d.fees().plus(d.correction()));
            recognized.put(LedgerPart.FEES, rec.fees());
            gross.put(LedgerPart.HONORARIUM, g.honorarium());
            discount.put(LedgerPart.HONORARIUM, d.honorarium());
            recognized.put(LedgerPart.HONORARIUM, rec.honorarium());
            parts.add(new TitleParts(t.titleId(), gross, discount, recognized));
        }
        return List.copyOf(parts);
    }

    /** Publica a mudança de status do acordo com evento de negócio, auditoria e métrica. */
    void statusChanged(Agreement a, StatusChange change, Instant at) {
        events.publishEvent(new AgreementStatusChanged(Ids.newId(), currentActor.tenantId(), at, a.getId(), a.getCaseId(),
                a.getDebtorId(), a.getCreditorId(), a.getPortfolioId(), a.titleIds(),
                change.previous() == null ? null : change.previous().name(), change.current().name(),
                a.getTotalAmount().amount(), a.getReplacedAgreementId()));
        metrics.agreementTransition(change.current().name(), a.getOrigin().name());
        String type = switch (change.current()) {
            case FORMALIZED -> BusinessEventTypes.AGREEMENT_FORMALIZED;
            case SETTLED -> BusinessEventTypes.AGREEMENT_SETTLED;
            case BROKEN -> BusinessEventTypes.AGREEMENT_BROKEN;
            case CANCELLED -> BusinessEventTypes.AGREEMENT_CANCELLED;
            case RENEGOTIATED -> BusinessEventTypes.AGREEMENT_RENEGOTIATED;
            case IN_PAYMENT -> null;
        };
        if (change.previous() != null) {
            auditTrail.record(AuditEntry.of("agreement.status_changed", AgreementService.RESOURCE, a.getId())
                    .creditor(a.getCreditorId()).change("status", change.previous(), change.current())
                    .meta("paidAmount", a.getPaidAmount().amount()).meta("replacedBy", a.getReplacedByAgreementId()));
        }
        if (type == null) {
            return;
        }
        long daysSinceFormalization = java.time.Duration.between(a.getFormalizedAt(), at).toDays();
        businessEvents.log(BusinessEvent.of(type).creditor(a.getCreditorId()).portfolio(a.getPortfolioId())
                .debtor(a.getDebtorId()).collectionCase(a.getCaseId()).reference(AgreementService.RESOURCE, a.getId())
                .channel(a.getChannel()).amount(change.current() == AgreementStatus.FORMALIZED ? a.getTotalAmount()
                        : a.getPaidAmount())
                .attr("actorType", a.getCloserType()).attr("origin", a.getOrigin())
                .attr("concessionLevel", a.getConcessionLevel()).attr("envelopeUsage", a.getEnvelopeUsage().value())
                .attr("installments", a.getInstallmentCount()).attr("policyVersion", a.getPolicyVersion())
                .attr("daysOverdue", a.getMaxDaysOverdue()).attr("renegotiationNumber", a.getRenegotiationNumber())
                .attr("totalAmount", a.getTotalAmount().amount()).attr("paidAmount", a.getPaidAmount().amount())
                .attr("discountAmount", a.getDiscountAmount().amount())
                .attr("incrementalAmount", change.current() == AgreementStatus.FORMALIZED
                        ? a.getIncrementalAmount().amount() : null)
                .attr("previousStatus", change.previous())
                .attr("daysSinceFormalization", change.current() == AgreementStatus.FORMALIZED ? null : daysSinceFormalization)
                .attr("replacedAgreementId", a.getReplacedAgreementId())
                .attr("replacedByAgreementId", a.getReplacedByAgreementId()));
    }

    void installmentChanged(Agreement a, InstallmentChange c, Instant at) {
        var i = c.installment();
        events.publishEvent(new InstallmentStatusChanged(Ids.newId(), currentActor.tenantId(), at, i.getId(), a.getId(),
                a.getDebtorId(), a.getCreditorId(), i.getNumber(), i.getDueDate(), i.getAmount().amount(),
                i.getPaidAmount().amount(), c.previous().name(), c.current().name()));
        if (c.current() == InstallmentStatus.OVERDUE || c.current() == InstallmentStatus.PAID) {
            boolean overdue = c.current() == InstallmentStatus.OVERDUE;
            businessEvents.log(BusinessEvent.of(overdue ? BusinessEventTypes.INSTALLMENT_OVERDUE
                            : BusinessEventTypes.INSTALLMENT_PAID).creditor(a.getCreditorId()).portfolio(a.getPortfolioId())
                    .debtor(a.getDebtorId()).collectionCase(a.getCaseId()).reference(AgreementService.RESOURCE, a.getId())
                    .amount(overdue ? i.getAmount().minus(i.getPaidAmount()) : i.getAmount())
                    .attr("installmentNumber", i.getNumber()).attr("installments", a.getInstallmentCount())
                    .attr("dueDate", i.getDueDate()).attr("origin", a.getOrigin()).attr("actorType", a.getCloserType())
                    .attr("previousStatus", c.previous()));
            if (overdue) {
                metrics.installmentOverdue();
            }
        }
    }
}
