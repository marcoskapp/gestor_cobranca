package br.com.gestorcobranca.sandbox.domain.service;

import br.com.gestorcobranca.sandbox.domain.model.DemoHistory.Economics;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory.HistoryCreditor;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory.Trend;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.MonthDay;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Regras do volume do histórico fictício (decisão 1353), puras e determinísticas: sazonalidade do mês, tendência do
 * credor ao longo do ano, perfil econômico (esforço por contato, conversão e cumprimento) e dias de operação. A carga
 * transforma esses números em operação pelos casos de uso.
 */
public final class HistoryPlanner {

    /** Feriados nacionais fixos: sem operação no histórico (a janela da assessoria não os bloqueia sozinha). */
    private static final Set<MonthDay> HOLIDAYS = Set.of(MonthDay.of(1, 1), MonthDay.of(4, 21), MonthDay.of(5, 1),
            MonthDay.of(9, 7), MonthDay.of(10, 12), MonthDay.of(11, 2), MonthDay.of(11, 15), MonthDay.of(11, 20),
            MonthDay.of(12, 25));

    private HistoryPlanner() {
    }

    /**
     * Sazonalidade da cobrança (1 = mês médio): janeiro com as dívidas das festas, novembro e dezembro com 13º salário
     * e mutirões de fim de ano, fevereiro e junho mais fracos.
     */
    public static double seasonality(Month month) {
        return switch (month) {
            case JANUARY -> 1.20;
            case FEBRUARY -> 0.85;
            case MARCH -> 1.05;
            case APRIL -> 0.95;
            case MAY -> 1.00;
            case JUNE -> 0.85;
            case JULY -> 1.05;
            case AUGUST -> 1.00;
            case SEPTEMBER -> 0.95;
            case OCTOBER -> 1.00;
            case NOVEMBER -> 1.15;
            case DECEMBER -> 1.30;
        };
    }

    /**
     * Fator de volume do credor no mês {@code index} do histórico (0 = primeiro de {@code months}): zero antes da
     * primeira carteira; credor novo começa em 50% e chega ao normal em três meses.
     */
    public static double factor(HistoryCreditor creditor, int index, int months, Month calendarMonth) {
        int start = creditor.startMonth();
        if (index < start) {
            return 0;
        }
        double position = months <= 1 ? 1 : (double) index / (months - 1);
        double trend = switch (creditor.trend()) {
            case GROWING -> 0.6 + 0.8 * position;
            case DECLINING -> 1.4 - 0.8 * position;
            case STABLE -> 1.0;
            case SEASONAL -> 1.0;
        };
        double season = seasonality(calendarMonth);
        if (creditor.trend() == Trend.SEASONAL) {
            // educação e saúde: matrícula/reajuste no início do ano e volta às aulas no meio do ano
            season = switch (calendarMonth) {
                case JANUARY, FEBRUARY -> 1.6;
                case JULY, AUGUST -> 1.35;
                case JUNE, DECEMBER -> 0.6;
                default -> 0.9;
            };
        }
        double ramp = Math.min(1.0, 0.5 + 0.17 * (index - start));
        return trend * season * ramp;
    }

    /** Contatos registrados por unidade de peso do credor, por mês. */
    public static double interactionsPerWeight(Economics economics) {
        return switch (economics) {
            case SCALES -> 1.3;
            case EFFICIENT -> 1.0;
            case COSTLY -> 2.6;
            case LOSS -> 2.4;
        };
    }

    /** Acordos por unidade de peso do credor, por mês. */
    public static double agreementsPerWeight(Economics economics) {
        return switch (economics) {
            case SCALES -> 0.55;
            case EFFICIENT -> 0.45;
            case COSTLY -> 0.35;
            case LOSS -> 0.15;
        };
    }

    /** Duração de um contato (minutos, mínimo e máximo) pelo perfil: quem dá prejuízo exige contatos longos. */
    public static int[] contactMinutes(Economics economics) {
        return switch (economics) {
            case SCALES -> new int[]{2, 6};
            case EFFICIENT -> new int[]{3, 7};
            case COSTLY -> new int[]{8, 16};
            case LOSS -> new int[]{12, 26};
        };
    }

    /** Ajuste do cumprimento do acordo pelo perfil do credor (multiplica a chance do negociador). */
    public static double fulfillmentModifier(Economics economics) {
        return switch (economics) {
            case SCALES -> 1.1;
            case EFFICIENT -> 1.0;
            case COSTLY -> 0.85;
            case LOSS -> 0.55;
        };
    }

    /** Quantidade inteira a partir de um valor esperado, com o resto sorteado (média igual ao esperado). */
    public static int count(double expected, FictitiousData random) {
        if (expected <= 0) {
            return 0;
        }
        int whole = (int) Math.floor(expected);
        double rest = expected - whole;
        return whole + (random.between(0, 999) < rest * 1000 ? 1 : 0);
    }

    public static boolean businessDay(LocalDate day) {
        return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY
                && !HOLIDAYS.contains(MonthDay.from(day));
    }

    /**
     * Dias de operação registrados no mês: {@code perMonth} dias úteis espalhados pelo mês, só antes de
     * {@code untilExclusive} (o mês corrente vai até ontem).
     */
    public static List<LocalDate> operatingDays(YearMonth month, LocalDate untilExclusive, int perMonth) {
        List<LocalDate> business = new ArrayList<>();
        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
            if (businessDay(d) && d.isBefore(untilExclusive)) {
                business.add(d);
            }
        }
        if (business.size() <= perMonth) {
            return business;
        }
        List<LocalDate> chosen = new ArrayList<>();
        double step = (double) business.size() / perMonth;
        for (int i = 0; i < perMonth; i++) {
            chosen.add(business.get((int) Math.floor(i * step + step / 2)));
        }
        return chosen;
    }

    /** Dias úteis do intervalo (inclusive), para o tempo logado. */
    public static List<LocalDate> businessDays(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (businessDay(d)) {
                days.add(d);
            }
        }
        return days;
    }

    /**
     * Histórico já registrado por uma carga que chegou ao fim: o último mês (anterior a {@code today}) fechado na
     * remuneração variável ou — com a carência dele ainda correndo ({@code today} antes de {@code lastCloseableFrom})
     * — o mês anterior a ele fechado. Uma nova subida nesse estado não refaz os meses abertos (refazer duplicaria fatos).
     */
    public static boolean historyDone(boolean lastClosed, LocalDate lastCloseableFrom, boolean previousClosed,
                                      LocalDate today) {
        if (lastClosed) {
            return true;
        }
        boolean gracePending = lastCloseableFrom != null && today.isBefore(lastCloseableFrom);
        return gracePending && previousClosed;
    }
}
