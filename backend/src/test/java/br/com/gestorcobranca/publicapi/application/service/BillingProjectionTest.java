package br.com.gestorcobranca.publicapi.application.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Projeção até o fim do ciclo pela média diária. */
class BillingProjectionTest {

    @Test
    void projectsDailyAverageOverRemainingDaysRoundingUp() {
        // 100 em 10 dias, faltam 20: 100 + 100 ÷ 10 × 20 = 300
        assertThat(BillingService.projectOne(100, 10, 20)).isEqualTo(300);
        // 10 em 3 dias, faltam 1: 10 + 3,33… → 14
        assertThat(BillingService.projectOne(10, 3, 1)).isEqualTo(14);
        // último dia, nada a projetar; sem consumo, zero
        assertThat(BillingService.projectOne(55, 31, 0)).isEqualTo(55);
        assertThat(BillingService.projectOne(0, 5, 10)).isZero();
    }
}
