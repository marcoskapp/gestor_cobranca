package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.debt.domain.model.PrescriptionRule.Terms;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrescriptionRuleTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    private static Terms terms(int months, int offset, int alert) {
        return new Terms(months, PrescriptionBase.TITLE_DUE_DATE, offset, InterruptionPolicy.FIRST_ONLY, alert, " CC 206 ");
    }

    @Test
    void marketTemplateRuleStaysPendingUntilValidatedOrChanged() {
        PrescriptionRule rule = PrescriptionRule.fromMarketTemplate(InstrumentType.CHECK, PrescriptionClaim.EXECUTION,
                terms(6, 30, 60));
        assertThat(rule.getReviewStatus()).isEqualTo(RuleReviewStatus.PENDING_LEGAL_REVIEW);
        assertThat(rule.getLegalBasis()).isEqualTo("CC 206");
        assertThat(rule.isValidated()).isFalse();

        UUID reviewer = UUID.randomUUID();
        assertThat(rule.validate(reviewer, NOW)).isTrue();
        assertThat(rule.isValidated()).isTrue();
        assertThat(rule.getReviewedBy()).isEqualTo(reviewer);
        assertThat(rule.validate(reviewer, NOW)).isFalse();
    }

    @Test
    void updateMarksValidatedAndReportsWhetherCalculationIsAffected() {
        PrescriptionRule rule = PrescriptionRule.fromMarketTemplate(InstrumentType.CHECK, PrescriptionClaim.EXECUTION,
                terms(6, 30, 60));
        assertThat(rule.affectsCalculation(terms(6, 30, 90))).isFalse();
        assertThat(rule.affectsCalculation(terms(6, 60, 60))).isTrue();

        assertThat(rule.update(terms(6, 30, 60), UUID.randomUUID(), NOW)).isFalse();
        assertThat(rule.isValidated()).isTrue();
        assertThat(rule.update(terms(6, 60, 60), UUID.randomUUID(), NOW)).isTrue();
        assertThat(rule.getStartOffsetDays()).isEqualTo(60);
    }

    @Test
    void validatesTermsRanges() {
        assertThatThrownBy(() -> PrescriptionRule.validated(InstrumentType.CCB, PrescriptionClaim.COLLECTION,
                terms(0, 0, 90), UUID.randomUUID(), NOW))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "prescription_rule.term_invalid");
        assertThatThrownBy(() -> PrescriptionRule.validated(InstrumentType.CCB, PrescriptionClaim.COLLECTION,
                terms(361, 0, 90), UUID.randomUUID(), NOW)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> PrescriptionRule.validated(InstrumentType.CCB, PrescriptionClaim.COLLECTION,
                terms(60, 366, 90), UUID.randomUUID(), NOW))
                .hasFieldOrPropertyWithValue("code", "prescription_rule.start_offset_invalid");
        assertThatThrownBy(() -> PrescriptionRule.validated(InstrumentType.CCB, PrescriptionClaim.COLLECTION,
                terms(60, 0, 0), UUID.randomUUID(), NOW))
                .hasFieldOrPropertyWithValue("code", "prescription_rule.alert_days_invalid");
        assertThatThrownBy(() -> PrescriptionRule.validated(InstrumentType.CCB, null, terms(60, 0, 90), UUID.randomUUID(),
                NOW)).hasFieldOrPropertyWithValue("code", "prescription_rule.claim_required");
    }

    @Test
    void titlePrescriptionAlertsOncePerEstimatedDate() {
        PrescriptionRule rule = PrescriptionRule.fromMarketTemplate(InstrumentType.CONTRACT, PrescriptionClaim.COLLECTION,
                terms(12, 0, 90));
        LocalDate today = LocalDate.of(2026, 10, 23);
        TitlePrescription p = new TitlePrescription(UUID.randomUUID(),
                new PrescriptionCalculation(PrescriptionClaim.COLLECTION, today.minusMonths(11), today.minusMonths(11),
                        rule.getId(), 12, today.plusMonths(1), java.util.Set.of()));

        assertThat(p.needsAlert(today, 20)).isFalse();
        assertThat(p.needsAlert(today, 60)).isTrue();
        p.markAlerted(NOW);
        assertThat(p.needsAlert(today, 60)).isFalse();
        assertThat(p.getLastAlertedFor()).isEqualTo(today.plusMonths(1));

        // nova data estimada (ex.: interrupção) volta a ser elegível ao alerta quando entrar na janela
        p.apply(new PrescriptionCalculation(PrescriptionClaim.COLLECTION, today.minusMonths(11), today.minusDays(10),
                rule.getId(), 12, today.minusDays(10).plusMonths(12), java.util.Set.of()));
        assertThat(p.needsAlert(today, 60)).isFalse();
        assertThat(p.needsAlert(today, 400)).isTrue();
        // já prescrita: fora da janela
        p.apply(new PrescriptionCalculation(PrescriptionClaim.COLLECTION, today.minusYears(2), today.minusYears(2),
                rule.getId(), 12, today.minusYears(1), java.util.Set.of()));
        assertThat(p.needsAlert(today, 400)).isFalse();
        assertThatThrownBy(() -> p.apply(new PrescriptionCalculation(PrescriptionClaim.EXECUTION, today, today,
                rule.getId(), 12, today.plusYears(1), java.util.Set.of()))).isInstanceOf(IllegalArgumentException.class);
    }
}
