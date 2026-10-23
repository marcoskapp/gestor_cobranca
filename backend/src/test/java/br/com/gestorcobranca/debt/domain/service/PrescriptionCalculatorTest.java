package br.com.gestorcobranca.debt.domain.service;

import br.com.gestorcobranca.debt.domain.model.InstrumentType;
import br.com.gestorcobranca.debt.domain.model.InterruptionPolicy;
import br.com.gestorcobranca.debt.domain.model.PrescriptionBase;
import br.com.gestorcobranca.debt.domain.model.PrescriptionCalculation;
import br.com.gestorcobranca.debt.domain.model.PrescriptionClaim;
import br.com.gestorcobranca.debt.domain.model.PrescriptionRule;
import br.com.gestorcobranca.debt.domain.service.PrescriptionCalculator.InterruptionFact;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrescriptionCalculatorTest {

    private static final LocalDate DUE = LocalDate.of(2024, 3, 10);

    static PrescriptionRule rule(int months, PrescriptionBase base, int offsetDays, InterruptionPolicy policy) {
        return PrescriptionRule.fromMarketTemplate(InstrumentType.CONTRACT, PrescriptionClaim.COLLECTION,
                new PrescriptionRule.Terms(months, base, offsetDays, policy, 90, null));
    }

    private static InterruptionFact at(LocalDate date) {
        return new InterruptionFact(UUID.randomUUID(), date);
    }

    @Test
    void countsTermInMonthsFromTitleDueDateWithoutInterruptions() {
        PrescriptionRule rule = rule(60, PrescriptionBase.TITLE_DUE_DATE, 0, InterruptionPolicy.FIRST_ONLY);

        PrescriptionCalculation c = PrescriptionCalculator.calculate(DUE, LocalDate.of(2025, 1, 10), rule, List.of());

        assertThat(c.claim()).isEqualTo(PrescriptionClaim.COLLECTION);
        assertThat(c.ruleId()).isEqualTo(rule.getId());
        assertThat(c.baseDate()).isEqualTo(DUE);
        assertThat(c.countingStart()).isEqualTo(DUE);
        assertThat(c.termMonths()).isEqualTo(60);
        assertThat(c.estimatedDate()).isEqualTo(LocalDate.of(2029, 3, 10));
        assertThat(c.effectiveInterruptions()).isEmpty();
    }

    @Test
    void shortTermsAndStartOffsetModelCheckExecution() {
        // cheque: 6 meses após o fim do prazo de apresentação (30 dias)
        PrescriptionRule check = rule(6, PrescriptionBase.TITLE_DUE_DATE, 30, InterruptionPolicy.FIRST_ONLY);

        PrescriptionCalculation c = PrescriptionCalculator.calculate(DUE, null, check, List.of());

        assertThat(c.baseDate()).isEqualTo(DUE);
        assertThat(c.countingStart()).isEqualTo(LocalDate.of(2024, 4, 9));
        assertThat(c.estimatedDate()).isEqualTo(LocalDate.of(2024, 10, 9));
    }

    @Test
    void contractFinalDueDateBaseUsesLastInstallmentOnlyWhenLater() {
        PrescriptionRule rule = rule(60, PrescriptionBase.CONTRACT_FINAL_DUE_DATE, 0, InterruptionPolicy.FIRST_ONLY);
        LocalDate lastInstallment = LocalDate.of(2025, 8, 10);

        assertThat(PrescriptionCalculator.calculate(DUE, lastInstallment, rule, List.of()).estimatedDate())
                .isEqualTo(LocalDate.of(2030, 8, 10));
        // título sem contrato ou já o último: vale o próprio vencimento
        assertThat(PrescriptionCalculator.calculate(DUE, null, rule, List.of()).baseDate()).isEqualTo(DUE);
        assertThat(PrescriptionCalculator.calculate(DUE, DUE.minusMonths(1), rule, List.of()).baseDate()).isEqualTo(DUE);
    }

    @Test
    void monthArithmeticClampsToLastDayOfMonth() {
        PrescriptionRule rule = rule(6, PrescriptionBase.TITLE_DUE_DATE, 0, InterruptionPolicy.FIRST_ONLY);

        assertThat(PrescriptionCalculator.calculate(LocalDate.of(2023, 8, 31), null, rule, List.of()).estimatedDate())
                .isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    void firstOnlyPolicyRestartsCountingOnlyOnce() {
        PrescriptionRule rule = rule(36, PrescriptionBase.TITLE_DUE_DATE, 0, InterruptionPolicy.FIRST_ONLY);
        InterruptionFact protest = at(LocalDate.of(2025, 5, 20));
        InterruptionFact acknowledgement = at(LocalDate.of(2026, 1, 15));

        PrescriptionCalculation c = PrescriptionCalculator.calculate(DUE, null, rule, List.of(acknowledgement, protest));

        assertThat(c.countingStart()).isEqualTo(protest.date());
        assertThat(c.estimatedDate()).isEqualTo(LocalDate.of(2028, 5, 20));
        assertThat(c.effectiveInterruptions()).containsExactly(protest.id());
    }

    @Test
    void everyPolicyRestartsCountingOnEachTimelyInterruption() {
        PrescriptionRule rule = rule(36, PrescriptionBase.TITLE_DUE_DATE, 0, InterruptionPolicy.EVERY);
        InterruptionFact protest = at(LocalDate.of(2025, 5, 20));
        InterruptionFact citation = at(LocalDate.of(2027, 2, 1));

        PrescriptionCalculation c = PrescriptionCalculator.calculate(DUE, null, rule, List.of(citation, protest));

        assertThat(c.countingStart()).isEqualTo(citation.date());
        assertThat(c.estimatedDate()).isEqualTo(LocalDate.of(2030, 2, 1));
        assertThat(c.effectiveInterruptions()).containsExactlyInAnyOrder(protest.id(), citation.id());
    }

    @Test
    void interruptionAfterPrescriptionDoesNotRevivePrescribedClaim() {
        PrescriptionRule rule = rule(36, PrescriptionBase.TITLE_DUE_DATE, 0, InterruptionPolicy.EVERY);
        InterruptionFact late = at(LocalDate.of(2027, 3, 11));

        PrescriptionCalculation c = PrescriptionCalculator.calculate(DUE, null, rule, List.of(late));

        assertThat(c.estimatedDate()).isEqualTo(LocalDate.of(2027, 3, 10));
        assertThat(c.effectiveInterruptions()).isEmpty();
    }

    @Test
    void interruptionOnTheEstimatedDateIsStillTimely() {
        PrescriptionRule rule = rule(36, PrescriptionBase.TITLE_DUE_DATE, 0, InterruptionPolicy.FIRST_ONLY);
        InterruptionFact lastDay = at(LocalDate.of(2027, 3, 10));

        PrescriptionCalculation c = PrescriptionCalculator.calculate(DUE, null, rule, List.of(lastDay));

        assertThat(c.estimatedDate()).isEqualTo(LocalDate.of(2030, 3, 10));
        assertThat(c.effectiveInterruptions()).containsExactly(lastDay.id());
    }

    @Test
    void interruptionBeforeCountingStartsHasNoEffect() {
        PrescriptionRule check = rule(6, PrescriptionBase.TITLE_DUE_DATE, 30, InterruptionPolicy.FIRST_ONLY);
        InterruptionFact early = at(DUE.plusDays(10));
        InterruptionFact timely = at(LocalDate.of(2024, 6, 1));

        PrescriptionCalculation c = PrescriptionCalculator.calculate(DUE, null, check, List.of(early, timely));

        // a interrupção anterior ao início da contagem não consome a única interrupção admitida
        assertThat(c.effectiveInterruptions()).containsExactly(timely.id());
        assertThat(c.estimatedDate()).isEqualTo(LocalDate.of(2024, 12, 1));
    }

    @Test
    void secondInterruptionAfterRestartedTermEndsIsIgnoredWithEveryPolicy() {
        PrescriptionRule rule = rule(12, PrescriptionBase.TITLE_DUE_DATE, 0, InterruptionPolicy.EVERY);
        InterruptionFact first = at(LocalDate.of(2024, 12, 1));
        InterruptionFact tooLate = at(LocalDate.of(2026, 1, 2));

        PrescriptionCalculation c = PrescriptionCalculator.calculate(DUE, null, rule, List.of(first, tooLate));

        assertThat(c.estimatedDate()).isEqualTo(LocalDate.of(2025, 12, 1));
        assertThat(c.effectiveInterruptions()).containsExactly(first.id());
    }

    @Test
    void sameDayInterruptionsAreOrderedDeterministically() {
        PrescriptionRule rule = rule(36, PrescriptionBase.TITLE_DUE_DATE, 0, InterruptionPolicy.FIRST_ONLY);
        LocalDate day = LocalDate.of(2025, 1, 1);
        InterruptionFact a = new InterruptionFact(UUID.fromString("00000000-0000-0000-0000-000000000001"), day);
        InterruptionFact b = new InterruptionFact(UUID.fromString("00000000-0000-0000-0000-000000000002"), day);

        assertThat(PrescriptionCalculator.calculate(DUE, null, rule, List.of(b, a)).effectiveInterruptions())
                .containsExactly(a.id());
    }

    @Test
    void requiresDueDateAndRule() {
        PrescriptionRule rule = rule(36, PrescriptionBase.TITLE_DUE_DATE, 0, InterruptionPolicy.FIRST_ONLY);
        assertThatThrownBy(() -> PrescriptionCalculator.calculate(null, null, rule, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PrescriptionCalculator.calculate(DUE, null, null, List.of()))
                .isInstanceOf(NullPointerException.class);
    }
}
