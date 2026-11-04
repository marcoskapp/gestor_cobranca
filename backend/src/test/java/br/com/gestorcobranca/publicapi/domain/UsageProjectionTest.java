package br.com.gestorcobranca.publicapi.domain;

import br.com.gestorcobranca.publicapi.domain.model.BillableUsage;
import br.com.gestorcobranca.publicapi.domain.service.UsageProjection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Projeção até o fim do ciclo pela média diária (comum à posição do credor e à da plataforma). */
class UsageProjectionTest {

    @Test
    void projectsDailyAverageOverRemainingDaysRoundingUp() {
        // 100 em 10 dias, faltam 20: 100 + 100 ÷ 10 × 20 = 300
        assertThat(UsageProjection.dailyAverage(100, 10, 20)).isEqualTo(300);
        // 10 em 3 dias, faltam 1: 10 + 3,33… → 14
        assertThat(UsageProjection.dailyAverage(10, 3, 1)).isEqualTo(14);
        // último dia, nada a projetar; sem consumo, zero
        assertThat(UsageProjection.dailyAverage(55, 31, 0)).isEqualTo(55);
        assertThat(UsageProjection.dailyAverage(0, 5, 10)).isZero();
    }

    @Test
    void projectsEachBillableQuantity() {
        assertThat(UsageProjection.dailyAverage(new BillableUsage(100, 10, 3, 0), 10, 20))
                .isEqualTo(new BillableUsage(300, 30, 9, 0));
    }
}
