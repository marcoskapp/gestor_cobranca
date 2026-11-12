package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.compliance.application.port.spi.RetentionDefaults.RetentionDefault;
import br.com.gestorcobranca.scoring.application.ScoringProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A janela de fatos das features ({@code app.scoring.fact-lookback-days}) vai ao compliance como mínimo exigido da
 * retenção dos fatos: o compliance recusa a subida se ela passar do mínimo da categoria (RetentionDefaultRegistryTest).
 */
class ScoringRetentionDefaultsTest {

    private static ScoringProperties properties(Integer lookbackDays) {
        return new ScoringProperties(null, null, null, null, null, lookbackDays, null, null, null, null, null);
    }

    @Test
    void declaresTheFactLookbackAsTheMinimumOfTheCaseFactsRetention() {
        assertThat(new ScoringRetentionDefaults(properties(900)).retentionDefaults())
                .containsExactly(new RetentionDefault("SCORING_CASE_FACTS", 1095, 900),
                        new RetentionDefault("SCORING_FEATURE_SNAPSHOTS", 730, 0),
                        new RetentionDefault("SCORING_CONTACT_FACTS", 1095, 0));
        assertThat(new ScoringRetentionDefaults(properties(null)).retentionDefaults().getFirst().requiredMinimum())
                .isEqualTo(730);
    }
}
