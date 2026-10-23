package br.com.gestorcobranca.legal.domain.service;

import br.com.gestorcobranca.legal.domain.model.LegalEligibilityRule;
import br.com.gestorcobranca.legal.domain.model.MatterFlag;
import br.com.gestorcobranca.legal.domain.model.PrescribedTitlePolicy;
import br.com.gestorcobranca.legal.domain.service.EligibilityEvaluator.CaseFacts;
import br.com.gestorcobranca.legal.domain.service.EligibilityEvaluator.Outcome;
import br.com.gestorcobranca.legal.domain.service.EligibilityEvaluator.TitleFacts;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EligibilityEvaluatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    private static final Set<String> STATUSES = Set.of("OPEN", "IN_NEGOTIATION");

    private static TitleFacts title(String balance, long days, boolean collateral, boolean executive,
                                    boolean collectionPrescribed, LocalDate nearest) {
        return new TitleFacts(Ids.newId(), Money.of(balance), days, "OPEN", false, collateral, executive, false,
                collectionPrescribed, nearest, true, true);
    }

    private static LegalEligibilityRule rule(Integer minDays, String minBalance, boolean collateral, boolean executive,
                                             Integer broken, Integer prescriptionDays) {
        return new LegalEligibilityRule(Ids.newId(), null, new LegalEligibilityRule.Criteria("Regra", minDays,
                minBalance == null ? null : Money.of(minBalance), collateral, executive, broken, prescriptionDays, false));
    }

    private static Outcome evaluate(CaseFacts facts, LegalEligibilityRule rule, PrescribedTitlePolicy policy) {
        return EligibilityEvaluator.evaluate(facts, rule, policy, STATUSES, TODAY, 180);
    }

    @Test
    void allCriteriaOfTheRuleMustBeMet() {
        CaseFacts facts = new CaseFacts(Ids.newId(), "OPEN",
                List.of(title("3000.00", 200, true, true, false, TODAY.plusYears(3))), 2, false);
        assertThat(evaluate(facts, rule(180, "2000.00", true, true, 2, null), PrescribedTitlePolicy.EXCLUDE).eligible())
                .isTrue();
        Outcome strict = evaluate(facts, rule(365, "5000.00", false, false, 3, 90), PrescribedTitlePolicy.EXCLUDE);
        assertThat(strict.eligible()).isFalse();
        assertThat(strict.unmet()).containsExactly(EligibilityEvaluator.MIN_DAYS_OVERDUE, EligibilityEvaluator.MIN_OPEN_BALANCE,
                EligibilityEvaluator.MIN_BROKEN_AGREEMENTS, EligibilityEvaluator.PRESCRIPTION_NOT_NEAR);
        CaseFacts noCollateral = new CaseFacts(Ids.newId(), "OPEN",
                List.of(title("3000.00", 200, false, false, false, null)), 0, false);
        assertThat(evaluate(noCollateral, rule(null, null, true, true, null, null), PrescribedTitlePolicy.EXCLUDE).unmet())
                .containsExactly(EligibilityEvaluator.COLLATERAL_REQUIRED, EligibilityEvaluator.EXECUTIVE_TITLE_REQUIRED);
    }

    @Test
    void prescribedTitlesAreExcludedByDefaultAndFlagged() {
        TitleFacts live = title("1000.00", 400, false, false, false, TODAY.plusDays(60));
        TitleFacts prescribed = title("500.00", 2000, false, false, true, null);
        CaseFacts facts = new CaseFacts(Ids.newId(), "OPEN", List.of(live, prescribed), 0, false);
        Outcome excluded = evaluate(facts, null, PrescribedTitlePolicy.EXCLUDE);
        assertThat(excluded.eligible()).isTrue();
        assertThat(excluded.titleIds()).containsExactly(live.titleId());
        assertThat(excluded.prescribedExcluded()).containsExactly(prescribed.titleId());
        assertThat(excluded.openBalance()).isEqualTo(Money.of("1000.00"));
        assertThat(excluded.flags()).contains(MatterFlag.PRESCRIBED_TITLES_EXCLUDED, MatterFlag.PRESCRIPTION_NEAR);

        Outcome flagged = evaluate(facts, null, PrescribedTitlePolicy.FLAG);
        assertThat(flagged.titleIds()).hasSize(2);
        assertThat(flagged.flags()).contains(MatterFlag.PRESCRIBED_TITLES_INCLUDED);

        CaseFacts allPrescribed = new CaseFacts(Ids.newId(), "OPEN", List.of(prescribed), 0, false);
        Outcome none = evaluate(allPrescribed, null, PrescribedTitlePolicy.EXCLUDE);
        assertThat(none.eligible()).isFalse();
        assertThat(none.unmet()).containsExactly(EligibilityEvaluator.ALL_TITLES_PRESCRIBED);
    }

    @Test
    void prescriptionNearCriterion() {
        CaseFacts near = new CaseFacts(Ids.newId(), "OPEN", List.of(title("800.00", 100, false, false, false,
                TODAY.plusDays(30))), 0, false);
        assertThat(evaluate(near, rule(null, null, false, false, null, 60), PrescribedTitlePolicy.EXCLUDE).eligible()).isTrue();
        assertThat(evaluate(near, rule(null, null, false, false, null, 10), PrescribedTitlePolicy.EXCLUDE).unmet())
                .containsExactly(EligibilityEvaluator.PRESCRIPTION_NOT_NEAR);
    }

    @Test
    void caseStatusDisputedTitlesAndExecutionPrescription() {
        UUID disputedId = Ids.newId();
        TitleFacts disputed = new TitleFacts(disputedId, Money.of("100.00"), 100, "OPEN", true, false, false, false, false,
                null, false, false);
        TitleFacts executionPrescribed = new TitleFacts(Ids.newId(), Money.of("100.00"), 100, "OPEN", false, false, true,
                true, false, null, true, false);
        TitleFacts settled = new TitleFacts(Ids.newId(), Money.of("100.00"), 100, "SETTLED", false, false, false, false,
                false, null, false, false);
        CaseFacts facts = new CaseFacts(Ids.newId(), "DISPUTED", List.of(disputed, executionPrescribed, settled), 0, false);
        Outcome o = evaluate(facts, rule(null, null, false, true, null, null), PrescribedTitlePolicy.EXCLUDE);
        assertThat(o.unmet()).containsExactly(EligibilityEvaluator.CASE_STATUS, EligibilityEvaluator.EXECUTIVE_TITLE_REQUIRED);
        assertThat(o.disputedExcluded()).containsExactly(disputedId);
        assertThat(o.titleIds()).containsExactly(executionPrescribed.titleId());
        assertThat(o.flags()).contains(MatterFlag.DISPUTED_TITLES_EXCLUDED, MatterFlag.EXECUTION_PRESCRIBED,
                MatterFlag.PRESCRIPTION_RULE_UNVALIDATED);
    }

    @Test
    void cadenceCriterionRequiresAnExhaustedExtrajudicialCadence() {
        LegalEligibilityRule cadence = new LegalEligibilityRule(Ids.newId(), null, new LegalEligibilityRule.Criteria(
                "Régua esgotada", null, null, false, false, null, null, true));
        CaseFacts without = new CaseFacts(Ids.newId(), "OPEN", List.of(title("900.00", 60, false, false, false, null)),
                0, false);
        assertThat(evaluate(without, cadence, PrescribedTitlePolicy.EXCLUDE).unmet())
                .containsExactly(EligibilityEvaluator.CADENCE_NOT_EXHAUSTED);
        CaseFacts exhausted = new CaseFacts(Ids.newId(), "OPEN", List.of(title("900.00", 60, false, false, false, null)),
                0, true);
        assertThat(evaluate(exhausted, cadence, PrescribedTitlePolicy.EXCLUDE).eligible()).isTrue();
    }
}
