package br.com.gestorcobranca.publicapi.domain.service;

import br.com.gestorcobranca.publicapi.domain.model.Proration;
import br.com.gestorcobranca.shared.domain.Guard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

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

    /**
     * Ciclo que começa em {@code start} e fecha na primeira data de fechamento igual ou posterior, limitado a
     * {@code lastDay} (fim da vigência do contrato ou véspera do início do contrato seguinte — decisão 624): o último
     * ciclo de um contrato termina no fim da vigência e fica proporcional ({@link #proration}).
     *
     * @param lastDay último dia possível do ciclo, ou {@code null} sem limite
     */
    public static Cycle startingAt(LocalDate start, int closingDay, LocalDate lastDay) {
        LocalDate end = closingDateOnOrAfter(start, closingDay);
        if (lastDay != null && !lastDay.isBefore(start) && lastDay.isBefore(end)) {
            end = lastDay;
        }
        return new Cycle(start, end, closingDay);
    }

    /**
     * Primeiro ciclo do calendário de um contrato (que começa no início da vigência, decisão 624) que termina em
     * {@code since} ou depois — o fechamento automático não alcança ciclos encerrados antes do registro das condições
     * de cobrança (sem retroatividade). Se a vigência termina antes de {@code since}, devolve o último ciclo do
     * contrato, encerrado antes de {@code since} (quem chama o descarta).
     *
     * @param lastDay último dia do calendário do contrato, ou {@code null} se a vigência é indeterminada
     */
    public static Cycle firstEndingOnOrAfter(LocalDate contractStart, LocalDate lastDay, int closingDay,
                                             LocalDate since) {
        Cycle cycle = startingAt(contractStart, closingDay, lastDay);
        while (cycle.end().isBefore(since) && (lastDay == null || cycle.end().isBefore(lastDay))) {
            cycle = startingAt(cycle.end().plusDays(1), closingDay, lastDay);
        }
        return cycle;
    }

    /** Ciclo (no calendário puro do dia de fechamento) que contém a data. */
    public static Cycle containing(LocalDate day, int closingDay) {
        LocalDate end = closingDateOnOrAfter(day, closingDay);
        LocalDate previousEnd = closingDateIn(YearMonth.from(end).minusMonths(1), closingDay);
        return new Cycle(previousEnd.plusDays(1), end, closingDay);
    }

    /**
     * Proporcionalidade de um ciclo (decisão 621, do responsável): dias do ciclo ÷ dias do <i>ciclo de referência</i> —
     * o ciclo regular, no calendário do dia de fechamento, que termina na mesma data de fechamento. Base real/real
     * (dias corridos do próprio período, como nas assinaturas com cobrança proporcional), e não mês comercial de 30
     * dias: um ciclo regular tem sempre fator 1, seja de 28, 30 ou 31 dias; só o ciclo de transição (início fora do
     * calendário, após mudança do dia de fechamento) fica proporcional.
     */
    public static Proration proration(Cycle cycle) {
        Cycle reference = containing(cycle.end(), cycle.closingDay());
        return new Proration(days(cycle), days(reference));
    }

    private static int days(Cycle c) {
        return (int) ChronoUnit.DAYS.between(c.start(), c.end()) + 1;
    }

    private static void requireDay(int closingDay) {
        Guard.require(closingDay >= 1 && closingDay <= 31, "billing_cycle.closing_day_invalid",
                "Dia de fechamento deve estar entre 1 e 31");
    }
}
