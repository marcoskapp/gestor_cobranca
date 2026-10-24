package br.com.gestorcobranca.settlement.domain.service;

import br.com.gestorcobranca.shared.domain.Guard;

import java.time.LocalDate;

/**
 * Ciclo mensal da prestação de contas pelo dia de fechamento do contrato
 * ({@code ServiceContract.settlementDay}, 1–28): o ciclo que fecha no dia D do mês M vai do dia D do mês
 * M−1 ao dia D−1 do mês M (D = 1: mês civil anterior).
 */
public final class SettlementCycle {

    private SettlementCycle() {
    }

    public record Period(LocalDate start, LocalDate end) {
    }

    /** Ciclo que contém a data. */
    public static Period containing(LocalDate date, int settlementDay) {
        Guard.require(settlementDay >= 1 && settlementDay <= 28, "settlement.cycle_day_invalid",
                "Dia de fechamento deve estar entre 1 e 28");
        LocalDate start = date.getDayOfMonth() >= settlementDay
                ? date.withDayOfMonth(settlementDay)
                : date.minusMonths(1).withDayOfMonth(settlementDay);
        return new Period(start, start.plusMonths(1).minusDays(1));
    }

    /** Último ciclo encerrado antes de {@code today}. */
    public static Period lastClosed(LocalDate today, int settlementDay) {
        return containing(containing(today, settlementDay).start().minusDays(1), settlementDay);
    }
}
