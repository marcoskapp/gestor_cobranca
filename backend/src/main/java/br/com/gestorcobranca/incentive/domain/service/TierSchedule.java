package br.com.gestorcobranca.incentive.domain.service;

import br.com.gestorcobranca.incentive.domain.model.tier.TierPeriodicity;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Quando a rotina abre uma rodada de classificação: uma rodada automática por período (mensal: a partir do 1º dia útil
 * do mês; semanal: a partir da segunda-feira). A rodada é <b>devida no período</b>, não só no dia de início: se a rotina
 * não rodou no dia (instância fora, falha, feriado móvel), ela abre a rodada na próxima execução do mesmo período. A
 * idempotência ("já há rodada automática criada desde o início do período?") fica com o serviço.
 */
public final class TierSchedule {

    private TierSchedule() {
    }

    /**
     * Início do período corrente.
     *
     * @param firstBusinessDayOfMonth 1º dia útil do mês de {@code today} (calendário de feriados da assessoria)
     */
    public static LocalDate periodStart(TierPeriodicity periodicity, LocalDate today, LocalDate firstBusinessDayOfMonth) {
        return switch (periodicity) {
            case MONTHLY -> firstBusinessDayOfMonth;
            case WEEKLY -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        };
    }

    /**
     * O período já começou (a rodada é devida se ainda não houver rodada automática desde o início dele)? Falso só no
     * mês antes do 1º dia útil (ex.: 1º de janeiro).
     *
     * @param firstBusinessDayOfMonth 1º dia útil do mês de {@code today} (calendário de feriados da assessoria)
     */
    public static boolean isDue(TierPeriodicity periodicity, LocalDate today, LocalDate firstBusinessDayOfMonth) {
        return !today.isBefore(periodStart(periodicity, today, firstBusinessDayOfMonth));
    }
}
