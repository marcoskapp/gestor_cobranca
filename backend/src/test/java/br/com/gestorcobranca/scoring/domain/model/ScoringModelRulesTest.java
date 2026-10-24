package br.com.gestorcobranca.scoring.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoringModelRulesTest {

    static final Instant T0 = Instant.parse("2026-10-23T05:00:00Z");

    static ScoringModel model(ModelStatus status) {
        return new ScoringModel(2, ModelKind.SCORECARD, status, null, 30, "{}", BandCutoffs.DEFAULT,
                new BigDecimal("0.30"), null, null, null, null, UUID.randomUUID(), T0);
    }

    @Test
    void bandsFollowTheCutoffs() {
        BandCutoffs b = BandCutoffs.DEFAULT;
        assertThat(List.of(b.band(1000), b.band(400), b.band(399), b.band(250), b.band(150), b.band(149), b.band(75),
                b.band(74), b.band(0))).containsExactly("A", "A", "B", "B", "C", "D", "D", "E", "E");
        assertThatThrownBy(() -> new BandCutoffs(100, 200, 50, 10)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void lifecycleOnlyPromotesOrRejectsTheChallenger() {
        ScoringModel challenger = model(ModelStatus.CHALLENGER);
        ScoringModel champion = model(ModelStatus.CHAMPION);
        assertThatThrownBy(() -> champion.promote(UUID.randomUUID(), T0)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> model(ModelStatus.FAILED_VALIDATION).promote(UUID.randomUUID(), T0))
                .isInstanceOf(ConflictException.class);
        champion.retire(T0);
        challenger.promote(UUID.randomUUID(), T0);
        assertThat(champion.getStatus()).isEqualTo(ModelStatus.RETIRED);
        assertThat(challenger.getStatus()).isEqualTo(ModelStatus.CHAMPION);
        assertThatThrownBy(() -> challenger.reject(T0)).isInstanceOf(ConflictException.class);
        ScoringModel other = model(ModelStatus.CHALLENGER);
        other.reject(T0);
        assertThat(other.getStatus()).isEqualTo(ModelStatus.REJECTED);
        assertThat(other.defineReferenceDistribution("{\"a\":1}")).isTrue();
        assertThat(other.defineReferenceDistribution("{\"b\":2}")).isFalse();
    }

    @Test
    void decisionRequiresJustification() {
        assertThatThrownBy(() -> new ModelDecision(UUID.randomUUID(), ModelDecision.Decision.PROMOTE, null, "curta", "{}",
                UUID.randomUUID(), T0)).isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("scoring.decision.justification_required");
    }

    @Test
    void scoreIsPublishedOnFirstCalculationBandChangeOrRelevantDelta() {
        ScoringModel m = model(ModelStatus.CHAMPION);
        CaseScore s = new CaseScore(UUID.randomUUID(), UUID.randomUUID());
        s.record(UUID.randomUUID(), UUID.randomUUID(), m, evaluation(0.300), "{}", "[]", LocalDate.of(2026, 10, 23), T0,
                CaseScore.Trigger.BATCH);
        assertThat(s.getBand()).isEqualTo("B");
        assertThat(s.shouldPublish(10)).isTrue();
        s.markPublished(T0);
        s.record(s.getCreditorId(), s.getPortfolioId(), m, evaluation(0.305), "{}", "[]", LocalDate.of(2026, 10, 24), T0,
                CaseScore.Trigger.BATCH);
        assertThat(s.shouldPublish(10)).isFalse();
        s.record(s.getCreditorId(), s.getPortfolioId(), m, evaluation(0.312), "{}", "[]", LocalDate.of(2026, 10, 25), T0,
                CaseScore.Trigger.BATCH);
        assertThat(s.shouldPublish(10)).isTrue();
        s.record(s.getCreditorId(), s.getPortfolioId(), m, evaluation(0.405), "{}", "[]", LocalDate.of(2026, 10, 26), T0,
                CaseScore.Trigger.BATCH);
        assertThat(s.getBand()).isEqualTo("A");
        assertThat(s.shouldPublish(500)).isTrue();
    }

    @Test
    void settingsValidateParametersAndLegalBasis() {
        ScoringSettings.Parameters ok = new ScoringSettings.Parameters(30, 500, 50, 10, new BigDecimal("0.30"),
                BigDecimal.ONE, new BigDecimal("0.60"), 365, 7, 10, new BigDecimal("0.10"), new BigDecimal("0.25"),
                BandCutoffs.DEFAULT, new BigDecimal("0.30"), true);
        ScoringSettings s = new ScoringSettings(ok);
        assertThat(s.legalBasisRegistered()).isFalse();
        assertThatThrownBy(() -> s.registerLegalBasis(LegalBasis.LEGITIMATE_INTEREST, " ", UUID.randomUUID(), T0))
                .isInstanceOf(BusinessRuleException.class);
        s.registerLegalBasis(LegalBasis.LEGITIMATE_INTEREST, "LIA-2026-07 aprovada pelo DPO", UUID.randomUUID(), T0);
        assertThat(s.legalBasisRegistered()).isTrue();
        assertThat(s.isSnapshotDay(LocalDate.ofEpochDay(70))).isTrue();
        assertThat(s.isSnapshotDay(LocalDate.ofEpochDay(71))).isFalse();
        assertThatThrownBy(() -> new ScoringSettings.Parameters(30, 50, 50, 10, new BigDecimal("0.30"), BigDecimal.ONE,
                new BigDecimal("0.60"), 365, 7, 10, new BigDecimal("0.10"), new BigDecimal("0.25"), BandCutoffs.DEFAULT,
                new BigDecimal("0.30"), true)).isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("scoring.settings.min_data_invalid");
        assertThatThrownBy(() -> new ScoringSettings.Parameters(30, 500, 50, 10, new BigDecimal("0.30"), BigDecimal.ONE,
                new BigDecimal("0.60"), 365, 7, 10, new BigDecimal("0.30"), new BigDecimal("0.25"), BandCutoffs.DEFAULT,
                new BigDecimal("0.30"), true)).isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("scoring.settings.psi_invalid");
    }

    private static Evaluation evaluation(double p) {
        return new Evaluation(p, Math.log(p / (1 - p)), List.of());
    }

    @Test
    void featureVectorRoundTripsThroughNames() {
        EnumMap<Feature, Double> v = new EnumMap<>(Feature.class);
        v.put(Feature.OPEN_BALANCE, 10.5);
        v.put(Feature.DAYS_SINCE_LAST_PAYMENT, Double.NaN);
        FeatureVector fv = FeatureVector.of(v);
        assertThat(fv.get(Feature.DAYS_SINCE_LAST_PAYMENT)).isNull();
        assertThat(FeatureVector.fromNames(fv.toNames())).isEqualTo(fv);
        assertThat(Feature.DAYS_SINCE_LAST_PAYMENT.transformed(null)).isZero();
        assertThat(Feature.DAYS_SINCE_LAST_PAYMENT.transformed(30.0)).isEqualTo(0.5);
        assertThat(Feature.OPEN_BALANCE.transformed(Math.E - 1)).isEqualTo(1.0);
        assertThat(Feature.PORTFOLIO_RECOVERY_RATE.transformed(null)).isNull();
    }
}
