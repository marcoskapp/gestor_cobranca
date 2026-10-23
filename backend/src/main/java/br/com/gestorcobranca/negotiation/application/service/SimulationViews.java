package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.CalculationLineView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ComponentsView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.CompositionView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ExcludedTitleView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.LimitsView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.OfferView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PaymentView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.TitleCompositionView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ViolationView;
import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityLimits;
import br.com.gestorcobranca.negotiation.domain.model.calculation.Breakdown;
import br.com.gestorcobranca.negotiation.domain.model.calculation.CalculationLine;
import br.com.gestorcobranca.negotiation.domain.model.calculation.DebtComposition;
import br.com.gestorcobranca.negotiation.domain.model.calculation.TitleComposition;
import br.com.gestorcobranca.negotiation.domain.model.offer.ScheduledPayment;
import br.com.gestorcobranca.negotiation.domain.model.offer.Violation;
import br.com.gestorcobranca.negotiation.domain.model.offer.ViolationKind;
import br.com.gestorcobranca.negotiation.domain.model.policy.ComponentRates;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules;
import br.com.gestorcobranca.negotiation.domain.service.DecimalMath;
import br.com.gestorcobranca.negotiation.domain.service.TermsEvaluator;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Conversão do domínio de cálculo para os records do caso de uso (valores em {@code BigDecimal}). */
final class SimulationViews {

    private SimulationViews() {
    }

    static ComponentsView components(Breakdown b) {
        return new ComponentsView(b.principal().amount(), b.correction().amount(), b.interest().amount(),
                b.fine().amount(), b.fees().amount(), b.honorarium().amount());
    }

    static ComponentsView rates(ComponentRates r) {
        return new ComponentsView(r.principal(), r.correction(), r.interest(), r.fine(), r.fees(), r.honorarium());
    }

    static ComponentRates rates(ComponentsView v) {
        return v == null ? ComponentRates.NONE
                : new ComponentRates(v.principal(), v.correction(), v.interest(), v.fine(), v.fees(), v.honorarium());
    }

    static CompositionView composition(DebtComposition c) {
        List<TitleCompositionView> titles = c.titles().stream().map(SimulationViews::title).toList();
        return new CompositionView(c.referenceDate(), titles, components(c.total()), c.total().total().amount(),
                c.paidTotal().amount(), c.maxDaysOverdue());
    }

    private static TitleCompositionView title(TitleComposition t) {
        return new TitleCompositionView(t.titleId(), t.portfolioId(), t.dueDate(), t.daysOverdue(), components(t.gross()),
                t.total().amount(), t.paidTotal().amount(), DecimalMath.display(t.correctionFactor()),
                t.lines().stream().map(SimulationViews::line).toList());
    }

    static CalculationLineView line(CalculationLine l) {
        return new CalculationLineView(l.titleId(), l.component().name(), l.rule().name(), l.base(), l.rate(), l.days(),
                l.factor(), l.amount().amount(), l.note());
    }

    static OfferView offer(NegotiationGuard.Evaluation e, String levelName) {
        var plan = e.plan();
        boolean valid = e.violations().stream().noneMatch(v -> v.kind() != ViolationKind.AUTHORITY);
        boolean withinAuthority = e.violations().isEmpty();
        return new OfferView(e.level(), levelName, plan.installments(), valid, withinAuthority,
                rates(e.priced().discountRates()), components(e.priced().discount()),
                e.priced().discount().total().amount(), components(e.priced().net()), plan.negotiated().amount(),
                plan.downPayment().amount(), plan.total().amount(), plan.financingInterest().amount(), plan.periodRate(),
                plan.schedule().stream().map(SimulationViews::payment).toList(),
                e.priced().lines().stream().map(SimulationViews::line).toList(),
                e.violations().stream().map(SimulationViews::violation).toList());
    }

    private static PaymentView payment(ScheduledPayment p) {
        return new PaymentView(p.number(), p.dueDate(), p.amount().amount(), p.interest().amount(),
                p.amortization().amount(), p.balanceAfter().amount());
    }

    static ViolationView violation(Violation v) {
        return new ViolationView(v.kind().name(), v.code(), v.message(), v.component() == null ? null : v.component().name(),
                v.titleId());
    }

    static List<ExcludedTitleView> excluded(Map<UUID, List<String>> excluded) {
        return excluded.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new ExcludedTitleView(e.getKey(), e.getValue())).toList();
    }

    /** Limites efetivos: mínimo(política, perfil). */
    static LimitsView limits(PolicyRules rules, AuthorityLimits a) {
        return new LimitsView(TermsEvaluator.maxLevel(rules, a), a.maxDiscountShare(),
                Math.min(rules.installments().maxInstallments(), a.maxInstallments()),
                a.maxAgreementAmount() == null ? null : a.maxAgreementAmount().amount(),
                rules.installments().minDownPaymentPercent().max(a.minDownPaymentShare()), a.canFormalize(),
                a.canApprove());
    }
}
