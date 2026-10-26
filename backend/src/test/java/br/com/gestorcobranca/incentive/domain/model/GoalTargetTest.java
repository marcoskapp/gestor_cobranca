package br.com.gestorcobranca.incentive.domain.model;

import br.com.gestorcobranca.incentive.domain.model.plan.GoalTarget;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalTarget.SubjectType;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalUnit;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Valor e unidade da meta do período (decisões 500 e 502). */
class GoalTargetTest {

    private static final LocalDate NOVEMBER = LocalDate.of(2026, 11, 1);

    private static GoalTarget goal(String value, GoalUnit unit) {
        return new GoalTarget(NOVEMBER, SubjectType.USER, Ids.newId(), new BigDecimal(value), unit);
    }

    @Test
    void currencyAcceptsCentsAndLargeValues() {
        assertThat(goal("80000.50", GoalUnit.CURRENCY).getUnit()).isEqualTo(GoalUnit.CURRENCY);
        assertThat(goal("999999999999999.99", GoalUnit.CURRENCY).getTargetValue()).isEqualByComparingTo("999999999999999.99");
        // casas zeradas além dos centavos não são precisão a mais
        assertThat(goal("150.5000", GoalUnit.CURRENCY).getTargetValue()).isEqualByComparingTo("150.50");
        assertRefused(() -> goal("150.505", GoalUnit.CURRENCY), "goal.target_scale_invalid");
        assertRefused(() -> goal("1000000000000000", GoalUnit.CURRENCY), "goal.target_too_large");
    }

    @Test
    void percentGoesFromOneHundredthToOneHundred() {
        assertThat(goal("0.01", GoalUnit.PERCENT).getTargetValue()).isEqualByComparingTo("0.01");
        assertThat(goal("100", GoalUnit.PERCENT).getTargetValue()).isEqualByComparingTo("100");
        assertThat(goal("12.75", GoalUnit.PERCENT).getUnit()).isEqualTo(GoalUnit.PERCENT);
        assertRefused(() -> goal("100.01", GoalUnit.PERCENT), "goal.percent_out_of_range");
        assertRefused(() -> goal("12.345", GoalUnit.PERCENT), "goal.target_scale_invalid");
    }

    @Test
    void unitAndPositiveValueAreRequired() {
        assertRefused(() -> goal("10", null), "goal.unit_required");
        assertRefused(() -> goal("0", GoalUnit.PERCENT), "goal.target_invalid");
        assertRefused(() -> goal("-5", GoalUnit.CURRENCY), "goal.target_invalid");
    }

    @Test
    void unitChangesOnlyTogetherWithTheValue() {
        GoalTarget g = goal("80000", GoalUnit.CURRENCY);
        // 80.000 não cabe em %: a troca de unidade exige o valor de novo, nunca reinterpreta o anterior
        assertRefused(() -> g.define(g.getTargetValue(), GoalUnit.PERCENT), "goal.percent_out_of_range");
        assertThat(g.getUnit()).isEqualTo(GoalUnit.CURRENCY);
        g.define(new BigDecimal("15"), GoalUnit.PERCENT);
        assertThat(g.getUnit()).isEqualTo(GoalUnit.PERCENT);
        assertThat(g.getTargetValue()).isEqualByComparingTo("15");
    }

    private static void assertRefused(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", code);
    }
}
