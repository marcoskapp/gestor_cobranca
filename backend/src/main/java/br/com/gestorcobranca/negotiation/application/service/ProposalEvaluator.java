package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.port.out.CaseEligibilityPort;
import br.com.gestorcobranca.negotiation.application.service.NegotiationGuard.Context;
import br.com.gestorcobranca.negotiation.application.service.NegotiationGuard.Evaluation;
import br.com.gestorcobranca.negotiation.application.service.NegotiationGuard.Outcome;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement;
import br.com.gestorcobranca.negotiation.domain.model.offer.PlanRequest;
import br.com.gestorcobranca.negotiation.domain.model.offer.Violation;
import br.com.gestorcobranca.negotiation.domain.model.offer.ViolationKind;
import br.com.gestorcobranca.negotiation.domain.model.policy.ComponentRates;
import br.com.gestorcobranca.negotiation.domain.repository.AgreementRepository;
import br.com.gestorcobranca.negotiation.domain.service.OfferCalculator;
import br.com.gestorcobranca.negotiation.domain.service.TermsEvaluator;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.security.Actor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Validação de condições de proposta (criação, aprovação, aceite e formalização) sobre o
 * {@link NegotiationGuard}, acrescentando as regras que só existem para propostas:
 * <ul>
 *   <li>{@code proposal.multiple_portfolios} (ELIGIBILITY): uma proposta cobre os títulos de um único
 *       caso de cobrança (uma carteira);</li>
 *   <li>{@code title.under_agreement} (ELIGIBILITY): título já coberto por acordo em vigor que não é o
 *       substituído pela renegociação;</li>
 *   <li>{@code authority.renegotiations_exceeded} (AUTHORITY): renegociações acima de
 *       {@code maxRenegotiations} do perfil do ator.</li>
 * </ul>
 */
@Component
class ProposalEvaluator {

    /**
     * @param discounts          desconto pedido por componente (nulo = limite do ator no nível e forma de pagamento)
     * @param renegotiationNumber posição na cadeia de renegociações (0 = acordo original)
     */
    record Terms(UUID creditorId, UUID debtorId, List<UUID> titleIds, LocalDate referenceDate, UUID expectedPolicyId,
                 boolean frozenReference, int level, boolean justified, ComponentRates discounts, PlanRequest plan,
                 UUID replacedAgreementId, int renegotiationNumber) {
    }

    /**
     * @param maxDiscount desconto máximo da política para a forma de pagamento (base do uso do envelope)
     * @param caseRef     caso de cobrança ativo da carteira dos títulos, se houver
     */
    record Result(Context ctx, Evaluation evaluation, ComponentRates discounts, List<Violation> violations,
                  Outcome outcome, Money maxDiscount, Optional<CaseEligibilityPort.CaseRef> caseRef) {

        List<String> authorityCodes() {
            return violations.stream().filter(v -> v.kind() == ViolationKind.AUTHORITY).map(Violation::code)
                    .distinct().toList();
        }

        List<String> blockingCodes() {
            return violations.stream().filter(v -> v.kind() != ViolationKind.AUTHORITY).map(Violation::code)
                    .distinct().toList();
        }
    }

    private final NegotiationGuard guard;
    private final AgreementRepository agreements;
    private final CaseEligibilityPort eligibility;
    private final OfferCalculator offers = new OfferCalculator();

    ProposalEvaluator(NegotiationGuard guard, AgreementRepository agreements, CaseEligibilityPort eligibility) {
        this.guard = guard;
        this.agreements = agreements;
        this.eligibility = eligibility;
    }

    Result evaluate(Actor actor, Terms t) {
        Context ctx = guard.prepare(actor, new NegotiationGuard.Request(t.creditorId(), t.debtorId(), t.titleIds(),
                t.referenceDate(), t.expectedPolicyId(), t.frozenReference()), true);
        int n = t.plan().installments();
        ComponentRates discounts = t.discounts() != null ? t.discounts()
                : TermsEvaluator.authorityLimit(ctx.rules(), t.level(), n, ctx.limits());
        Evaluation evaluation = guard.validate(ctx, t.level(), t.justified(), discounts, t.plan());
        List<Violation> violations = new ArrayList<>(evaluation.violations());
        if (ctx.portfolioId() == null) {
            violations.add(Violation.of(ViolationKind.ELIGIBILITY, "proposal.multiple_portfolios",
                    "A proposta deve cobrir títulos de uma única carteira (um caso de cobrança)"));
        }
        for (Agreement active : agreements.findActiveCovering(ctx.composition().titleIds())) {
            if (active.getId().equals(t.replacedAgreementId())) {
                continue;
            }
            active.titleIds().stream().filter(ctx.composition().titleIds()::contains).forEach(titleId -> violations.add(
                    new Violation(ViolationKind.ELIGIBILITY, "title.under_agreement",
                            "Título coberto por acordo em vigor", null, titleId)));
        }
        if (t.renegotiationNumber() > ctx.limits().maxRenegotiations()) {
            violations.add(Violation.of(ViolationKind.AUTHORITY, "authority.renegotiations_exceeded",
                    "Renegociações acima da alçada (máximo " + ctx.limits().maxRenegotiations() + ")"));
        }
        Money maxDiscount = offers.price(ctx.composition(), ctx.rules().charges(), ctx.rules().maxDiscountFor(n))
                .discount().total();
        Optional<CaseEligibilityPort.CaseRef> caseRef = ctx.portfolioId() == null ? Optional.empty()
                : eligibility.activeCase(t.debtorId(), ctx.portfolioId());
        return new Result(ctx, evaluation, discounts, List.copyOf(violations), NegotiationGuard.outcome(violations),
                maxDiscount, caseRef);
    }
}
