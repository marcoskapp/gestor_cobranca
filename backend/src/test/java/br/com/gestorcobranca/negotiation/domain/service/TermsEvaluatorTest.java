package br.com.gestorcobranca.negotiation.domain.service;

import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityLimits;
import br.com.gestorcobranca.negotiation.domain.model.calculation.DebtComponent;
import br.com.gestorcobranca.negotiation.domain.model.offer.PaymentPlan;
import br.com.gestorcobranca.negotiation.domain.model.offer.PlanRequest;
import br.com.gestorcobranca.negotiation.domain.model.offer.Violation;
import br.com.gestorcobranca.negotiation.domain.model.offer.ViolationKind;
import br.com.gestorcobranca.negotiation.domain.model.policy.ComponentRates;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules;
import br.com.gestorcobranca.negotiation.domain.model.policy.StandardPolicyTerms;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Política de mercado ({@link StandardPolicyTerms}): à vista até 100% de juros/multa, 50% de
 * honorários e 20% de principal; escada 0..3 (nível 1 = metade do envelope em multa e juros;
 * nível 3 só à vista).
 */
class TermsEvaluatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 11);
    private final PolicyRules rules = StandardPolicyTerms.marketDefault().base();
    private final TermsEvaluator evaluator = new TermsEvaluator();
    private final OfferCalculator offers = new OfferCalculator();

    private static AuthorityLimits junior() {
        return new AuthorityLimits(UUID.randomUUID(), "JUNIOR", 1, new BigDecimal("50"), 6, Money.of("5000"),
                new BigDecimal("10"), true, false, 0);
    }

    private static AuthorityLimits manager() {
        return new AuthorityLimits(UUID.randomUUID(), "GESTOR", 99, new BigDecimal("100"), 360, null, BigDecimal.ZERO, true,
                true, 3);
    }

    private List<Violation> evaluate(AuthorityLimits authority, int level, boolean justified, ComponentRates discounts,
                                     int installments, String negotiated, LocalDate firstDue) {
        Money net = Money.of(negotiated);
        PaymentPlan plan = offers.plan(net, new PlanRequest(installments, null, firstDue), rules.installments(),
                TermsEvaluator.minimumDownPayment(net, rules, authority));
        return evaluator.evaluate(new TermsEvaluator.Context(rules, authority, TODAY, TODAY),
                new TermsEvaluator.Terms(level, justified, discounts, plan));
    }

    @Test
    void effectiveLimitIsMinimumOfEnvelopeLevelAndProfile() {
        // à vista, nível 1: envelope juros 100% × fatia do nível 50% = 50%; perfil júnior 50% do envelope = 50%
        ComponentRates level1 = TermsEvaluator.authorityLimit(rules, 1, 1, junior());
        assertThat(level1.interest()).isEqualByComparingTo("50");
        assertThat(level1.honorarium()).isEqualByComparingTo("0");
        // nível 2, à vista: política libera 100% de juros e 50% de honorários; júnior limita a 50% e 25%
        ComponentRates level2 = TermsEvaluator.authorityLimit(rules, 2, 1, junior());
        assertThat(level2.interest()).isEqualByComparingTo("50");
        assertThat(level2.honorarium()).isEqualByComparingTo("25");
        // até 6x o envelope de juros cai para 60%: nível 1 → 30%
        assertThat(TermsEvaluator.policyLimit(rules, 1, 6).interest()).isEqualByComparingTo("30");
        // acima de 12x não há faixa: sem desconto
        assertThat(TermsEvaluator.policyLimit(rules, 2, 13).allZero()).isTrue();
    }

    @Test
    void offerAtTheActorLimitIsValid() {
        assertThat(evaluate(junior(), 1, true, TermsEvaluator.authorityLimit(rules, 1, 1, junior()), 1, "1000.00", TODAY))
                .isEmpty();
    }

    @Test
    void discountAbovePolicyIsPolicyViolationAndAboveProfileIsAuthorityViolation() {
        // juros 60% à vista no nível 1: política permite 50% (100% × 50%) → POLICY
        List<Violation> policy = evaluate(manager(), 1, true,
                new ComponentRates(null, null, new BigDecimal("60"), null, null, null), 1, "1000.00", TODAY);
        assertThat(policy).singleElement().satisfies(v -> {
            assertThat(v.kind()).isEqualTo(ViolationKind.POLICY);
            assertThat(v.code()).isEqualTo("policy.discount_exceeded");
            assertThat(v.component()).isEqualTo(DebtComponent.INTEREST);
        });
        // honorários 40% à vista no nível 2: política 50%, júnior 25% → AUTHORITY (e nível 2 acima da alçada 1)
        List<Violation> authority = evaluate(junior(), 2, true,
                new ComponentRates(null, null, null, null, null, new BigDecimal("40")), 1, "1000.00", TODAY);
        assertThat(authority).extracting(Violation::code)
                .containsExactlyInAnyOrder("authority.discount_exceeded", "authority.level_exceeded");
        assertThat(authority).allMatch(v -> v.kind() == ViolationKind.AUTHORITY);
    }

    @Test
    void escalationRequiresJustification() {
        assertThat(evaluate(junior(), 1, false, ComponentRates.NONE, 1, "1000.00", TODAY)).extracting(Violation::code)
                .containsExactly("policy.justification_required");
    }

    @Test
    void cashOnlyLevelRejectsInstallments() {
        assertThat(evaluate(manager(), 3, true, ComponentRates.NONE, 2, "1000.00", TODAY)).extracting(Violation::code)
                .containsExactly("policy.level_installments_exceeded");
    }

    @Test
    void installmentLimitsAndMinimums() {
        // 8x excede o júnior (6), não a política (12)
        assertThat(evaluate(junior(), 0, false, ComponentRates.NONE, 8, "1000.00", TODAY)).extracting(Violation::code)
                .containsExactly("authority.installments_exceeded");
        // 13x excede a política
        assertThat(evaluate(manager(), 0, false, ComponentRates.NONE, 13, "1000.00", TODAY)).extracting(Violation::code)
                .contains("policy.installments_exceeded");
        // 300,00 em 12x: entrada 30,00 e parcelas de 24,55 < mínimo de 50,00
        assertThat(evaluate(manager(), 0, false, ComponentRates.NONE, 12, "300.00", TODAY)).extracting(Violation::code)
                .containsExactly("policy.installment_below_minimum");
    }

    @Test
    void firstPaymentWindowAndAgreementAmount() {
        assertThat(evaluate(manager(), 0, false, ComponentRates.NONE, 1, "1000.00", TODAY.plusDays(11)))
                .extracting(Violation::code).containsExactly("policy.first_payment_out_of_range");
        assertThat(evaluate(junior(), 0, false, ComponentRates.NONE, 1, "5000.01", TODAY)).extracting(Violation::code)
                .containsExactly("authority.amount_exceeded");
    }

    @Test
    void explicitDownPaymentBelowMinimumIsFlagged() {
        Money net = Money.of("1000.00");
        PaymentPlan plan = offers.plan(net, new PlanRequest(4, Money.of("50.00"), TODAY), rules.installments(), Money.ZERO);
        List<Violation> v = evaluator.evaluate(new TermsEvaluator.Context(rules, junior(), TODAY, TODAY),
                new TermsEvaluator.Terms(0, false, ComponentRates.NONE, plan));
        assertThat(v).extracting(Violation::code).containsExactly("policy.down_payment_below_minimum");
    }

    @Test
    void maxLevelAndInstallmentsCombinePolicyLevelAndProfile() {
        assertThat(TermsEvaluator.maxLevel(rules, junior())).isEqualTo(1);
        assertThat(TermsEvaluator.maxLevel(rules, manager())).isEqualTo(3);
        assertThat(TermsEvaluator.maxInstallments(rules, 0, junior())).isEqualTo(6);
        assertThat(TermsEvaluator.maxInstallments(rules, 0, manager())).isEqualTo(12);
        assertThat(TermsEvaluator.maxInstallments(rules, 3, manager())).isEqualTo(1);
    }
}
