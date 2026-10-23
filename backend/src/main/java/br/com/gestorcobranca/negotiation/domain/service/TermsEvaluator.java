package br.com.gestorcobranca.negotiation.domain.service;

import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityLimits;
import br.com.gestorcobranca.negotiation.domain.model.calculation.DebtComponent;
import br.com.gestorcobranca.negotiation.domain.model.offer.PaymentPlan;
import br.com.gestorcobranca.negotiation.domain.model.offer.Violation;
import br.com.gestorcobranca.negotiation.domain.model.offer.ViolationKind;
import br.com.gestorcobranca.negotiation.domain.model.policy.ComponentRates;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.ConcessionLevel;
import br.com.gestorcobranca.shared.domain.Money;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Regras de validação de condições comerciais — o núcleo puro do {@code NegotiationGuard}.
 * Separa o que está fora da política ({@link ViolationKind#POLICY}: nunca pode ser concedido)
 * do que está dentro da política mas acima da alçada do ator ({@link ViolationKind#AUTHORITY}:
 * exige aprovação). O limite efetivo é sempre mínimo(envelope da política, perfil do ator).
 */
public final class TermsEvaluator {

    /**
     * @param today         data corrente no fuso de operação
     * @param referenceDate data-base do cálculo
     */
    public record Context(PolicyRules rules, AuthorityLimits authority, LocalDate today, LocalDate referenceDate) {
    }

    /**
     * @param level     nível da escada usado
     * @param justified há justificativa registrada (exigida acima do nível 0)
     * @param discounts desconto por componente (%)
     * @param plan      cronograma resultante
     */
    public record Terms(int level, boolean justified, ComponentRates discounts, PaymentPlan plan) {
    }

    public List<Violation> evaluate(Context ctx, Terms terms) {
        PolicyRules rules = ctx.rules();
        AuthorityLimits authority = ctx.authority();
        List<Violation> violations = new ArrayList<>();
        int n = terms.plan().installments();
        Optional<ConcessionLevel> level = rules.level(terms.level());
        if (level.isEmpty()) {
            violations.add(Violation.of(ViolationKind.POLICY, "policy.level_unknown",
                    "Nível " + terms.level() + " não existe na escada da política"));
            return violations;
        }
        if (terms.level() > 0 && !terms.justified()) {
            violations.add(Violation.of(ViolationKind.POLICY, "policy.justification_required",
                    "Subir de nível na escada exige justificativa"));
        }
        // parcelamento
        if (n > rules.installments().maxInstallments()) {
            violations.add(Violation.of(ViolationKind.POLICY, "policy.installments_exceeded",
                    "A política permite até " + rules.installments().maxInstallments() + " pagamentos"));
        }
        Integer levelMax = level.get().maxInstallments();
        if (levelMax != null && n > levelMax) {
            violations.add(Violation.of(ViolationKind.POLICY, "policy.level_installments_exceeded",
                    "O nível " + terms.level() + " permite até " + levelMax + " pagamentos"));
        }
        // descontos: envelope × fatia do nível (política) e × fatia do perfil (alçada)
        ComponentRates policyLimit = policyLimit(rules, terms.level(), n);
        ComponentRates authorityLimit = authorityLimit(rules, terms.level(), n, authority);
        for (DebtComponent c : DebtComponent.values()) {
            if (terms.discounts().get(c).compareTo(policyLimit.get(c)) > 0) {
                violations.add(new Violation(ViolationKind.POLICY, "policy.discount_exceeded",
                        "Desconto em " + c.name() + " acima do permitido pela política (" + policyLimit.get(c).toPlainString()
                                + "%)", c, null));
            } else if (terms.discounts().get(c).compareTo(authorityLimit.get(c)) > 0) {
                violations.add(new Violation(ViolationKind.AUTHORITY, "authority.discount_exceeded",
                        "Desconto em " + c.name() + " acima da alçada (" + authorityLimit.get(c).toPlainString() + "%)", c,
                        null));
            }
        }
        // valores e datas do cronograma
        PaymentPlan plan = terms.plan();
        if (n > 1) {
            Money policyMinimum = OfferCalculator.policyMinimumDownPayment(plan.negotiated(), rules.installments());
            Money authorityMinimum = OfferCalculator.shareOf(plan.negotiated(), authority.minDownPaymentShare());
            if (plan.downPayment().isLessThan(policyMinimum)) {
                violations.add(Violation.of(ViolationKind.POLICY, "policy.down_payment_below_minimum",
                        "Entrada mínima da política: " + policyMinimum.amount().toPlainString()));
            } else if (plan.downPayment().isLessThan(authorityMinimum)) {
                violations.add(Violation.of(ViolationKind.AUTHORITY, "authority.down_payment_below_minimum",
                        "Entrada mínima da alçada: " + authorityMinimum.amount().toPlainString()));
            }
            Money minInstallment = Money.of(rules.installments().minInstallmentAmount());
            if (plan.smallestInstallment().isLessThan(minInstallment)) {
                violations.add(Violation.of(ViolationKind.POLICY, "policy.installment_below_minimum",
                        "Parcela mínima da política: " + minInstallment.amount().toPlainString()));
            }
        }
        LocalDate first = plan.schedule().getFirst().dueDate();
        LocalDate latest = ctx.today().plusDays(rules.validity().maxDaysToFirstPayment());
        if (first.isBefore(ctx.referenceDate()) || first.isAfter(latest)) {
            violations.add(Violation.of(ViolationKind.POLICY, "policy.first_payment_out_of_range",
                    "O 1º pagamento deve vencer entre " + ctx.referenceDate() + " e " + latest));
        }
        // alçada: nível, parcelas e valor
        if (terms.level() > authority.maxConcessionLevel()) {
            violations.add(Violation.of(ViolationKind.AUTHORITY, "authority.level_exceeded",
                    "Nível acima da alçada (máximo " + authority.maxConcessionLevel() + ")"));
        }
        if (n > authority.maxInstallments()) {
            violations.add(Violation.of(ViolationKind.AUTHORITY, "authority.installments_exceeded",
                    "Parcelamento acima da alçada (máximo " + authority.maxInstallments() + ")"));
        }
        if (authority.maxAgreementAmount() != null && plan.total().isGreaterThan(authority.maxAgreementAmount())) {
            violations.add(Violation.of(ViolationKind.AUTHORITY, "authority.amount_exceeded",
                    "Valor do acordo acima da alçada"));
        }
        return violations;
    }

    /** Desconto máximo da política no nível e forma de pagamento: envelope(n) × fatia do nível. */
    public static ComponentRates policyLimit(PolicyRules rules, int level, int installments) {
        ComponentRates envelope = rules.maxDiscountFor(installments);
        return rules.level(level).map(l -> envelope.scaledBy(l.envelopeShare())).orElse(ComponentRates.NONE);
    }

    /** Limite efetivo do ator: mínimo(limite da política no nível, envelope(n) × fatia do perfil). */
    public static ComponentRates authorityLimit(PolicyRules rules, int level, int installments, AuthorityLimits authority) {
        return policyLimit(rules, level, installments)
                .min(rules.maxDiscountFor(installments).scaledBy(authority.maxDiscountShare()));
    }

    /** Maior nível que o ator pode usar sem aprovação. */
    public static int maxLevel(PolicyRules rules, AuthorityLimits authority) {
        return Math.min(rules.topLevel(), authority.maxConcessionLevel());
    }

    /** Maior número de pagamentos no nível, dentro da política e da alçada. */
    public static int maxInstallments(PolicyRules rules, int level, AuthorityLimits authority) {
        int max = Math.min(rules.installments().maxInstallments(), authority.maxInstallments());
        Integer levelMax = rules.level(level).map(ConcessionLevel::maxInstallments).orElse(null);
        return levelMax == null ? max : Math.min(max, levelMax);
    }

    /** Entrada mínima efetiva: maior entre a exigência da política e a da alçada. */
    public static Money minimumDownPayment(Money negotiated, PolicyRules rules, AuthorityLimits authority) {
        return OfferCalculator.policyMinimumDownPayment(negotiated, rules.installments())
                .max(OfferCalculator.shareOf(negotiated, authority.minDownPaymentShare()));
    }
}
