package br.com.gestorcobranca.publicapi.domain.service;

import br.com.gestorcobranca.publicapi.domain.model.BillableUsage;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Projeção do consumo até o fim do ciclo pela média diária, usada nas posições em tempo real das duas camadas de
 * cobrança (credor e plataforma): quantidade projetada = atual + atual ÷ dias decorridos × dias restantes, arredondada
 * para cima (uma unidade começada conta inteira, como nas franquias).
 */
public final class UsageProjection {

    public static final String METHOD = "DAILY_AVERAGE";

    private UsageProjection() {
    }

    /**
     * @param daysElapsed   dias do ciclo decorridos, contando hoje
     * @param daysRemaining dias que faltam depois de hoje
     */
    public static long dailyAverage(long current, int daysElapsed, int daysRemaining) {
        if (current == 0 || daysRemaining <= 0 || daysElapsed <= 0) {
            return current;
        }
        return current + BigDecimal.valueOf(current).multiply(BigDecimal.valueOf(daysRemaining))
                .divide(BigDecimal.valueOf(daysElapsed), 0, RoundingMode.CEILING).longValueExact();
    }

    /** Projeção de cada quantidade faturável (chamadas, MB recebidos e enviados, registros). */
    public static BillableUsage dailyAverage(BillableUsage current, int daysElapsed, int daysRemaining) {
        return new BillableUsage(dailyAverage(current.requests(), daysElapsed, daysRemaining),
                dailyAverage(current.mbIn(), daysElapsed, daysRemaining),
                dailyAverage(current.mbOut(), daysElapsed, daysRemaining),
                dailyAverage(current.records(), daysElapsed, daysRemaining));
    }
}
