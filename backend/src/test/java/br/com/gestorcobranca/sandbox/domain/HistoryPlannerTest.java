package br.com.gestorcobranca.sandbox.domain;

import br.com.gestorcobranca.sandbox.domain.model.DemoHistory;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory.HistoryCreditor;
import br.com.gestorcobranca.sandbox.domain.service.FictitiousData;
import br.com.gestorcobranca.sandbox.domain.service.HistoryPlanner;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryPlannerTest {

    private static HistoryCreditor creditor(String prefix) {
        return DemoHistory.of("aurora").orElseThrow().creditors().stream().filter(c -> c.prefix().equals(prefix))
                .findFirst().orElseThrow();
    }

    @Test
    void trendsRiseFallOrStayAcrossTheYear() {
        HistoryCreditor growing = creditor("MRD");
        HistoryCreditor declining = creditor("ATL");
        HistoryCreditor stable = creditor("SLN");
        // mesmo mês do calendário para isolar a tendência da sazonalidade
        assertThat(HistoryPlanner.factor(growing, 11, 12, Month.MAY))
                .isGreaterThan(HistoryPlanner.factor(growing, 3, 12, Month.MAY));
        assertThat(HistoryPlanner.factor(declining, 11, 12, Month.MAY))
                .isLessThan(HistoryPlanner.factor(declining, 3, 12, Month.MAY));
        assertThat(HistoryPlanner.factor(stable, 11, 12, Month.MAY))
                .isEqualTo(HistoryPlanner.factor(stable, 3, 12, Month.MAY));
    }

    @Test
    void newCreditorStartsLaterAndRampsUp() {
        HistoryCreditor late = creditor("TLC");
        assertThat(late.startMonth()).isEqualTo(6);
        assertThat(HistoryPlanner.factor(late, 5, 12, Month.MAY)).isZero();
        assertThat(HistoryPlanner.factor(late, 6, 12, Month.MAY))
                .isLessThan(HistoryPlanner.factor(late, 9, 12, Month.MAY));
    }

    @Test
    void decemberIsTheStrongestMonthAndSeasonalCreditorsPeakInJanuary() {
        assertThat(HistoryPlanner.seasonality(Month.DECEMBER)).isGreaterThan(HistoryPlanner.seasonality(Month.JUNE));
        HistoryCreditor school = creditor("EDP");
        assertThat(HistoryPlanner.factor(school, 4, 12, Month.JANUARY))
                .isGreaterThan(HistoryPlanner.factor(school, 4, 12, Month.JUNE) * 2);
    }

    @Test
    void lossMakingCreditorsNeedLongerContactsAndCloseFewerAgreements() {
        assertThat(HistoryPlanner.contactMinutes(DemoHistory.Economics.LOSS)[0])
                .isGreaterThan(HistoryPlanner.contactMinutes(DemoHistory.Economics.SCALES)[1]);
        assertThat(HistoryPlanner.agreementsPerWeight(DemoHistory.Economics.LOSS))
                .isLessThan(HistoryPlanner.agreementsPerWeight(DemoHistory.Economics.SCALES));
        assertThat(HistoryPlanner.fulfillmentModifier(DemoHistory.Economics.LOSS)).isLessThan(1);
    }

    @Test
    void countKeepsTheExpectedAverage() {
        FictitiousData random = new FictitiousData(42);
        int total = 0;
        for (int i = 0; i < 1000; i++) {
            total += HistoryPlanner.count(2.4, random);
        }
        assertThat(total).isBetween(2300, 2500);
        assertThat(HistoryPlanner.count(0, random)).isZero();
    }

    @Test
    void operatingDaysAreBusinessDaysSpreadThroughTheMonthAndBeforeToday() {
        List<LocalDate> days = HistoryPlanner.operatingDays(YearMonth.of(2025, 12), LocalDate.of(2026, 11, 17), 8);
        assertThat(days).hasSize(8).allMatch(HistoryPlanner::businessDay)
                .noneMatch(d -> d.getDayOfWeek() == DayOfWeek.SATURDAY || d.equals(LocalDate.of(2025, 12, 25)));
        assertThat(days.getFirst().getDayOfMonth()).isLessThan(6);
        assertThat(days.getLast().getDayOfMonth()).isGreaterThan(24);
        // mês corrente: só até ontem
        assertThat(HistoryPlanner.operatingDays(YearMonth.of(2026, 11), LocalDate.of(2026, 11, 17), 8))
                .allMatch(d -> d.isBefore(LocalDate.of(2026, 11, 17)));
        assertThat(HistoryPlanner.businessDays(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 17)))
                .containsExactly(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 17));
    }

    @Test
    void historicoConcluidoComUltimoMesFechadoOuNaCarenciaComOAnteriorFechado() {
        LocalDate today = LocalDate.of(2026, 12, 2);
        assertThat(HistoryPlanner.historyDone(true, null, false, today)).isTrue();
        assertThat(HistoryPlanner.historyDone(false, LocalDate.of(2026, 12, 5), true, today)).isTrue();
        // carência vencida e o mês não fechou: a carga não chegou ao fim
        assertThat(HistoryPlanner.historyDone(false, LocalDate.of(2026, 12, 1), true, today)).isFalse();
        // na carência, mas o mês anterior também aberto: carga interrompida
        assertThat(HistoryPlanner.historyDone(false, LocalDate.of(2026, 12, 5), false, today)).isFalse();
        assertThat(HistoryPlanner.historyDone(false, null, false, today)).isFalse();
    }
}
