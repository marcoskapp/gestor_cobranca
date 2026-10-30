package br.com.gestorcobranca.publicapi.domain.service;

import br.com.gestorcobranca.shared.domain.Guard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * Calendário dos ciclos de consumo das integrações (decisão 608). O contrato do credor define o dia de fechamento
 * (1–31): o ciclo fecha no fim desse dia e o seguinte começa sempre às 00:00 do dia seguinte, no fuso de negócio.
 * Meses sem o dia informado (por exemplo, 31 em fevereiro ou 30 em fevereiro) fecham no último dia do mês.
 * Função pura: as mesmas entradas produzem sempre os mesmos limites.
 */
public final class BillingCycles {

    private BillingCycles() {
    }

    /**
     * Ciclo de consumo com datas inclusivas no fuso de negócio.
     *
     * @param closingDay dia de fechamento usado para determinar {@code end}
     */
    public record Cycle(LocalDate start, LocalDate end, int closingDay) {

        public Cycle {
            Guard.require(start != null && end != null && !end.isBefore(start), "billing_cycle.invalid", "Ciclo inválido");
        }

        /** Instante de início (00:00 do primeiro dia, inclusivo). */
        public Instant from(ZoneId zone) {
            return start.atStartOfDay(zone).toInstant();
        }

        /** Instante de término (00:00 do dia seguinte ao fechamento, exclusivo) = início do próximo ciclo. */
        public Instant to(ZoneId zone) {
            return end.plusDays(1).atStartOfDay(zone).toInstant();
        }

        /** O ciclo terminou quando o próximo já começou. */
        public boolean endedAt(Instant now, ZoneId zone) {
            return !now.isBefore(to(zone));
        }
    }

    /** Dia de fechamento efetivo num mês: o informado ou, se o mês for mais curto, o último dia. */
    public static LocalDate closingDateIn(YearMonth month, int closingDay) {
        requireDay(closingDay);
        return month.atDay(Math.min(closingDay, month.lengthOfMonth()));
    }

    /** Primeira data de fechamento igual ou posterior a {@code start}. */
    public static LocalDate closingDateOnOrAfter(LocalDate start, int closingDay) {
        LocalDate candidate = closingDateIn(YearMonth.from(start), closingDay);
        return candidate.isBefore(start) ? closingDateIn(YearMonth.from(start).plusMonths(1), closingDay) : candidate;
    }

    /** Ciclo que começa em {@code start} e fecha na primeira data de fechamento igual ou posterior. */
    public static Cycle startingAt(LocalDate start, int closingDay) {
        return new Cycle(start, closingDateOnOrAfter(start, closingDay), closingDay);
    }

    /** Ciclo (no calendário puro do dia de fechamento) que contém a data. */
    public static Cycle containing(LocalDate day, int closingDay) {
        LocalDate end = closingDateOnOrAfter(day, closingDay);
        LocalDate previousEnd = closingDateIn(YearMonth.from(end).minusMonths(1), closingDay);
        return new Cycle(previousEnd.plusDays(1), end, closingDay);
    }

    private static void requireDay(int closingDay) {
        Guard.require(closingDay >= 1 && closingDay <= 31, "billing_cycle.closing_day_invalid",
                "Dia de fechamento deve estar entre 1 e 31");
    }
}
