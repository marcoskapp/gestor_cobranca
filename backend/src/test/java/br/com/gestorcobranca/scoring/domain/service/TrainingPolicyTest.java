package br.com.gestorcobranca.scoring.domain.service;

import br.com.gestorcobranca.scoring.domain.model.FeatureVector;
import br.com.gestorcobranca.scoring.domain.model.TrainingRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TrainingPolicyTest {

    static final TrainingPolicy.Criteria CRITERIA = new TrainingPolicy.Criteria(500, 50, 10, 0.30, 30);
    static final LocalDate D0 = LocalDate.of(2026, 1, 5);

    /** {@code weeks} datas semanais, {@code casesPerDate} casos distintos por data, 1 a cada {@code every} paga. */
    static List<TrainingRow> rows(int weeks, int casesPerDate, int every) {
        List<TrainingRow> rows = new ArrayList<>();
        List<UUID> cases = new ArrayList<>();
        for (int i = 0; i < casesPerDate; i++) {
            cases.add(UUID.randomUUID());
        }
        for (int w = 0; w < weeks; w++) {
            for (int i = 0; i < casesPerDate; i++) {
                rows.add(new TrainingRow(cases.get(i), D0.plusWeeks(w), FeatureVector.of(Map.of()), i % every == 0,
                        BigDecimal.TEN, BigDecimal.ONE));
            }
        }
        return rows;
    }

    @Test
    void belowMinimumDataStaysOnTheScorecard() {
        TrainingPolicy.Split split = TrainingPolicy.split(rows(10, 499, 5), CRITERIA);
        assertThat(split.sufficient()).isFalse();
        assertThat(split.reasons()).containsExactly("min_cases");
        assertThat(TrainingPolicy.split(rows(10, 600, 20), CRITERIA).reasons()).containsExactly("min_positives");
        assertThat(TrainingPolicy.split(rows(1, 600, 5), CRITERIA).reasons())
                .contains("single_reference_date");
    }

    @Test
    void outOfTimeSplitWithEmbargo() {
        TrainingPolicy.Split split = TrainingPolicy.split(rows(10, 600, 5), CRITERIA);
        assertThat(split.sufficient()).isTrue();
        assertThat(split.distinctCases()).isEqualTo(600);
        assertThat(split.distinctPositiveCases()).isEqualTo(120);
        // 30% de 6.000 = 1.800 → as 3 datas mais recentes (semanas 7, 8, 9)
        assertThat(split.validationFrom()).isEqualTo(D0.plusWeeks(7));
        assertThat(split.validation()).hasSize(1800);
        // treino só até 30 dias antes da validação: semanas 0 a 2 (semana 3 = 28 dias antes já fica de fora? não:
        // 49 − 30 = dia 19 → semanas 0, 1 e 2)
        assertThat(split.trainingTo()).isEqualTo(D0.plusWeeks(2));
        assertThat(split.training()).hasSize(1800);
        assertThat(split.training()).allSatisfy(r -> assertThat(r.referenceDate())
                .isBeforeOrEqualTo(split.validationFrom().minusDays(30)));
    }

    @Test
    void partitionsNeedEnoughPositivesAndNegatives() {
        // tudo pago: sem negativos
        TrainingPolicy.Split split = TrainingPolicy.split(rows(10, 600, 1), CRITERIA);
        assertThat(split.reasons()).contains("training_partition_too_small", "validation_partition_too_small");
    }
}
