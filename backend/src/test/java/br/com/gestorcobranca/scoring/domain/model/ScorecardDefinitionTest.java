package br.com.gestorcobranca.scoring.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ScorecardDefinitionTest {

    private static FeatureVector typical() {
        Map<Feature, Double> v = new EnumMap<>(Feature.class);
        v.put(Feature.DAYS_OVERDUE, 120.0);          //   0
        v.put(Feature.OPEN_BALANCE, 2000.0);         //   0
        v.put(Feature.EFFECTIVE_CONTACTS_90D, 0.0);  // −10
        v.put(Feature.DAYS_SINCE_EFFECTIVE_CONTACT, null); // −10 (nunca)
        v.put(Feature.RESPONSIVE_CHANNELS_90D, 0.0);
        v.put(Feature.PROMISES_KEPT, 0.0);
        v.put(Feature.PROMISES_BROKEN, 0.0);
        v.put(Feature.PRIOR_AGREEMENTS, 0.0);
        v.put(Feature.BROKEN_AGREEMENTS, 0.0);
        v.put(Feature.PARTIAL_PAYMENTS_365D, 0.0);
        v.put(Feature.ACTIVE_AGREEMENT, 0.0);
        v.put(Feature.PENDING_PROMISE, 0.0);
        return FeatureVector.of(v);
    }

    @Test
    void standardScorecardTurnsPointsIntoProbability() {
        ScorecardDefinition card = ScorecardDefinition.standard();
        assertThat(card.points(typical())).isEqualTo(480);
        // 480 pontos = 20 abaixo da âncora (500 = 1:9): chance cai pela metade → 1:18
        Evaluation e = card.evaluate(typical());
        assertThat(e.probability()).isCloseTo(1.0 / 19.0, within(1e-12));
        assertThat(e.score()).isEqualTo(53);
        assertThat(BandCutoffs.DEFAULT.band(e.score())).isEqualTo("E");
        assertThat(e.topFactors(5)).extracting(Evaluation.Contribution::feature)
                .containsExactly(Feature.EFFECTIVE_CONTACTS_90D, Feature.DAYS_SINCE_EFFECTIVE_CONTACT);
        assertThat(e.topFactors(5)).extracting(Evaluation.Contribution::points).containsExactly(-10, -10);
    }

    @Test
    void engagedDebtorWithPendingPromiseScoresHigher() {
        Map<Feature, Double> v = new EnumMap<>(Feature.class);
        v.put(Feature.DAYS_OVERDUE, 20.0);                 // +30
        v.put(Feature.OPEN_BALANCE, 300.0);                // +10
        v.put(Feature.EFFECTIVE_CONTACTS_90D, 2.0);        // +10
        v.put(Feature.DAYS_SINCE_EFFECTIVE_CONTACT, 3.0);  // +20
        v.put(Feature.PENDING_PROMISE, 1.0);               // +20
        ScorecardDefinition card = ScorecardDefinition.standard();
        assertThat(card.points(FeatureVector.of(v))).isEqualTo(590);
        double expected = 1 / (1 + Math.exp(-(Math.log(1.0 / 9) + 90 * Math.log(2) / 20)));
        assertThat(card.evaluate(FeatureVector.of(v)).probability()).isCloseTo(expected, within(1e-12));
        // contribuições somam o logit
        Evaluation e = card.evaluate(FeatureVector.of(v));
        double sum = Math.log(1.0 / 9) + e.contributions().stream().mapToDouble(Evaluation.Contribution::logOdds).sum();
        assertThat(e.logit()).isCloseTo(sum, within(1e-12));
    }

    @Test
    void rejectsOverlappingBinsAndRepeatedFeatures() {
        assertThatThrownBy(() -> new ScorecardDefinition.Rule(Feature.DAYS_OVERDUE, List.of(
                new ScorecardDefinition.Bin(0.0, 50.0, 1), new ScorecardDefinition.Bin(40.0, 90.0, 2)), 0))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("scorecard.bins_overlap");
        ScorecardDefinition.Rule rule = new ScorecardDefinition.Rule(Feature.DAYS_OVERDUE,
                List.of(new ScorecardDefinition.Bin(null, 30.0, 10)), 0);
        assertThatThrownBy(() -> new ScorecardDefinition(500, 20, 500, 1.0 / 9, List.of(rule, rule)))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("scorecard.feature_duplicated");
        assertThatThrownBy(() -> new ScorecardDefinition(500, 0, 500, 1.0 / 9, List.of(rule)))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("scorecard.pdo_invalid");
    }
}
