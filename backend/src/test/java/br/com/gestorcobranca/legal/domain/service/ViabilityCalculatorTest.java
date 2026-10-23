package br.com.gestorcobranca.legal.domain.service;

import br.com.gestorcobranca.legal.domain.model.LegalRoute;
import br.com.gestorcobranca.legal.domain.model.MatterFlag;
import br.com.gestorcobranca.legal.domain.service.ViabilityCalculator.CollateralFact;
import br.com.gestorcobranca.legal.domain.service.ViabilityCalculator.Input;
import br.com.gestorcobranca.legal.domain.service.ViabilityCalculator.Result;
import br.com.gestorcobranca.legal.domain.service.ViabilityCalculator.TitleFact;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Percentage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViabilityCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    private static TitleFact title(String balance, boolean executive, boolean executionPrescribed,
                                   boolean collectionPrescribed, LocalDate collectionDate) {
        return new TitleFact(Ids.newId(), Money.of(balance), executive, executive ? TODAY.plusYears(1) : null,
                executionPrescribed, collectionDate, collectionPrescribed, true, true);
    }

    private static Input input(List<TitleFact> titles, List<CollateralFact> collaterals, String other, String costs) {
        return new Input(titles, collaterals, Money.of(other), Percentage.of("70"), Percentage.of("50"), Money.of(costs),
                TODAY, 180);
    }

    @Test
    void securedDebtIsCappedByCollateralRecoveryAndSuggestsCollateralRoutes() {
        Result r = ViabilityCalculator.calculate(input(List.of(title("50000.00", true, false, false, TODAY.plusYears(3))),
                List.of(new CollateralFact(Ids.newId(), "REAL_ESTATE_FIDUCIARY_ALIENATION", "ACTIVE", Money.of("40000.00")),
                        new CollateralFact(Ids.newId(), "VEHICLE_FIDUCIARY_ALIENATION", "RELEASED", Money.of("9000.00"))),
                "0.00", "3000.00"));
        assertThat(r.debtAmount()).isEqualTo(Money.of("50000.00"));
        assertThat(r.collateralValue()).isEqualTo(Money.of("40000.00"));
        assertThat(r.collateralRecoverable()).isEqualTo(Money.of("28000.00"));
        assertThat(r.estimatedRecoverable()).isEqualTo(Money.of("14000.00"));
        assertThat(r.netBenefit()).isEqualTo(Money.of("11000.00"));
        assertThat(r.costBenefitRatio()).isEqualByComparingTo("4.67");
        assertThat(r.economicallyViable()).isTrue();
        assertThat(r.availableRoutes()).contains(LegalRoute.EXECUTION, LegalRoute.EXTRAJUDICIAL_COLLATERAL_ENFORCEMENT,
                LegalRoute.PROTEST).doesNotContain(LegalRoute.SEARCH_AND_SEIZURE);
        assertThat(r.suggestedRoutes()).startsWith(LegalRoute.EXTRAJUDICIAL_COLLATERAL_ENFORCEMENT, LegalRoute.EXECUTION);
    }

    @Test
    void unsecuredDebtUsesCollectibleBalanceAndProbability() {
        Result r = ViabilityCalculator.calculate(input(List.of(title("1000.00", false, false, false, TODAY.plusDays(90))),
                List.of(), "0.00", "200.00"));
        assertThat(r.estimatedRecoverable()).isEqualTo(Money.of("500.00"));
        assertThat(r.flags()).contains(MatterFlag.PRESCRIPTION_NEAR);
        assertThat(r.availableRoutes()).doesNotContain(LegalRoute.EXECUTION);
        assertThat(r.suggestedRoutes()).containsExactly(LegalRoute.PROTEST, LegalRoute.MONITORY_ACTION,
                LegalRoute.CREDIT_BUREAU_LISTING);
    }

    @Test
    void negativeCostBenefitAndPrescriptionLimitRoutes() {
        Result costly = ViabilityCalculator.calculate(input(List.of(title("300.00", false, false, false, TODAY.plusYears(2))),
                List.of(), "0.00", "800.00"));
        assertThat(costly.economicallyViable()).isFalse();
        assertThat(costly.flags()).contains(MatterFlag.NEGATIVE_COST_BENEFIT);
        assertThat(costly.suggestedRoutes()).isEmpty();

        Result executionGone = ViabilityCalculator.calculate(input(List.of(title("5000.00", true, true, false,
                TODAY.plusYears(2))), List.of(), "0.00", "100.00"));
        assertThat(executionGone.availableRoutes()).doesNotContain(LegalRoute.EXECUTION).contains(LegalRoute.MONITORY_ACTION);
        assertThat(executionGone.flags()).contains(MatterFlag.EXECUTION_PRESCRIBED);

        Result prescribed = ViabilityCalculator.calculate(input(List.of(title("5000.00", false, false, true, null)),
                List.of(), "0.00", "100.00"));
        assertThat(prescribed.collectibleDebt()).isEqualTo(Money.ZERO);
        assertThat(prescribed.availableRoutes()).isEmpty();
        assertThat(prescribed.economicallyViable()).isFalse();
        assertThat(prescribed.flags()).contains(MatterFlag.PRESCRIBED_TITLES_INCLUDED);
    }

    @Test
    void otherAssetsAndVehicleSeizure() {
        Result r = ViabilityCalculator.calculate(input(List.of(title("20000.00", false, false, false, TODAY.plusYears(3))),
                List.of(new CollateralFact(Ids.newId(), "VEHICLE_FIDUCIARY_ALIENATION", "ACTIVE", null)), "10000.00",
                "1000.00"));
        assertThat(r.otherAssetsRecoverable()).isEqualTo(Money.of("7000.00"));
        assertThat(r.estimatedRecoverable()).isEqualTo(Money.of("3500.00"));
        assertThat(r.suggestedRoutes()).startsWith(LegalRoute.SEARCH_AND_SEIZURE);
        assertThatThrownBy(() -> ViabilityCalculator.calculate(input(List.of(), List.of(), "-1.00", "0.00")))
                .isInstanceOf(BusinessRuleException.class);
    }
}
