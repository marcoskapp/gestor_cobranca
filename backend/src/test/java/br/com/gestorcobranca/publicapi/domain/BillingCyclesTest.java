package br.com.gestorcobranca.publicapi.domain;

import br.com.gestorcobranca.publicapi.domain.model.Proration;
import br.com.gestorcobranca.publicapi.domain.service.BillingCycles;
import br.com.gestorcobranca.publicapi.domain.service.BillingCycles.Cycle;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillingCyclesTest {

    private static final ZoneId ZONE = TimeConfig.BUSINESS_ZONE;

    @Test
    void day31ClosesOnTheLastDayOfShorterMonths() {
        assertThat(BillingCycles.closingDateIn(YearMonth.of(2026, 2), 31)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(BillingCycles.closingDateIn(YearMonth.of(2028, 2), 31)).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(BillingCycles.closingDateIn(YearMonth.of(2026, 4), 31)).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(BillingCycles.closingDateIn(YearMonth.of(2026, 2), 30)).isEqualTo(LocalDate.of(2026, 2, 28));

        // ciclo de fevereiro com dia 31: 01/02 a 28/02; o seguinte começa em 01/03 e fecha em 31/03
        Cycle february = BillingCycles.startingAt(LocalDate.of(2026, 2, 1), 31);
        assertThat(february.end()).isEqualTo(LocalDate.of(2026, 2, 28));
        Cycle march = BillingCycles.startingAt(february.end().plusDays(1), 31);
        assertThat(march.start()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(march.end()).isEqualTo(LocalDate.of(2026, 3, 31));
        // dia 30: fevereiro fecha em 28; março começa em 01/03 e fecha em 30/03
        assertThat(BillingCycles.startingAt(LocalDate.of(2026, 1, 31), 30).end()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(BillingCycles.startingAt(LocalDate.of(2026, 3, 1), 30).end()).isEqualTo(LocalDate.of(2026, 3, 30));
    }

    @Test
    void cycleClosesAtTheEndOfTheClosingDayAndNextStartsAtMidnightInBusinessZone() {
        Cycle cycle = BillingCycles.startingAt(LocalDate.of(2026, 3, 11), 10);
        assertThat(cycle.end()).isEqualTo(LocalDate.of(2026, 4, 10));
        // 00:00 de 11/03 e 00:00 de 11/04 em America/Sao_Paulo (UTC-3)
        assertThat(cycle.from(ZONE)).isEqualTo(Instant.parse("2026-03-11T03:00:00Z"));
        assertThat(cycle.to(ZONE)).isEqualTo(Instant.parse("2026-04-11T03:00:00Z"));
        // 23:59:59 do dia 10 (horário de Brasília) ainda é o ciclo; 00:00 do dia 11 já é o próximo
        assertThat(cycle.endedAt(Instant.parse("2026-04-11T02:59:59Z"), ZONE)).isFalse();
        assertThat(cycle.endedAt(Instant.parse("2026-04-11T03:00:00Z"), ZONE)).isTrue();
        Cycle next = BillingCycles.startingAt(cycle.end().plusDays(1), 10);
        assertThat(next.from(ZONE)).isEqualTo(cycle.to(ZONE));
    }

    @Test
    void cycleStartingOnTheClosingDayEndsTheSameDay() {
        Cycle cycle = BillingCycles.startingAt(LocalDate.of(2026, 3, 10), 10);
        assertThat(cycle.start()).isEqualTo(cycle.end());
    }

    @Test
    void containingDayUsesThePureCalendarOfTheClosingDay() {
        Cycle c = BillingCycles.containing(LocalDate.of(2026, 3, 5), 10);
        assertThat(c.start()).isEqualTo(LocalDate.of(2026, 2, 11));
        assertThat(c.end()).isEqualTo(LocalDate.of(2026, 3, 10));
        Cycle end = BillingCycles.containing(LocalDate.of(2026, 3, 1), 31);
        assertThat(end.start()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(end.end()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    void firstCycleOfTheContractRunsFromItsStartToTheFirstClosingDayAndIsProrated() {
        // contrato desde 20/01, fechamento dia 10: 20/01 a 10/02 — 22 dias do ciclo de referência 11/01–10/02 (31)
        Cycle first = BillingCycles.startingAt(LocalDate.of(2026, 1, 20), 10, null);
        assertThat(first.end()).isEqualTo(LocalDate.of(2026, 2, 10));
        assertThat(BillingCycles.proration(first)).isEqualTo(new Proration(22, 31));
        // contrato que começa no dia seguinte ao fechamento: ciclo cheio
        assertThat(BillingCycles.proration(BillingCycles.startingAt(LocalDate.of(2026, 1, 11), 10, null)).isFull())
                .isTrue();
    }

    @Test
    void lastCycleEndsAtTheEndOfTheContractAndIsProrated() {
        // vigência até 20/03, fechamento dia 10: 11/03 a 20/03 — 10 dias do ciclo de referência 11/03–10/04 (31)
        Cycle last = BillingCycles.startingAt(LocalDate.of(2026, 3, 11), 10, LocalDate.of(2026, 3, 20));
        assertThat(last.end()).isEqualTo(LocalDate.of(2026, 3, 20));
        assertThat(BillingCycles.proration(last)).isEqualTo(new Proration(10, 31));
        // fim da vigência depois do fechamento não corta o ciclo
        assertThat(BillingCycles.startingAt(LocalDate.of(2026, 3, 11), 10, LocalDate.of(2026, 6, 30)).end())
                .isEqualTo(LocalDate.of(2026, 4, 10));
        // contrato de poucos dias: começa e termina dentro do mesmo ciclo
        Cycle shortContract = BillingCycles.startingAt(LocalDate.of(2026, 3, 12), 10, LocalDate.of(2026, 3, 14));
        assertThat(BillingCycles.proration(shortContract)).isEqualTo(new Proration(3, 31));
    }

    @Test
    void automaticClosingStartsAtTheContractCycleThatEndsOnOrAfterTheConditionsWereRegistered() {
        // contrato desde 01/01/2025, condições registradas em 30/10/2026, fechamento dia 5: o primeiro ciclo alcançado
        // é 06/10–05/11/2026 — nenhum dos ~22 ciclos anteriores
        Cycle first = BillingCycles.firstEndingOnOrAfter(LocalDate.of(2025, 1, 1), null, 5, LocalDate.of(2026, 10, 30));
        assertThat(first.start()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(first.end()).isEqualTo(LocalDate.of(2026, 11, 5));
        // condições registradas antes do início: o primeiro ciclo é o do início do contrato
        Cycle fromStart = BillingCycles.firstEndingOnOrAfter(LocalDate.of(2026, 1, 20), null, 10,
                LocalDate.of(2025, 12, 1));
        assertThat(fromStart.start()).isEqualTo(LocalDate.of(2026, 1, 20));
        // vigência encerrada antes do registro: devolve o último ciclo do contrato, encerrado antes (descartado)
        Cycle ended = BillingCycles.firstEndingOnOrAfter(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 20), 10,
                LocalDate.of(2026, 1, 1));
        assertThat(ended.end()).isEqualTo(LocalDate.of(2025, 6, 20));
    }

    @Test
    void rejectsInvalidClosingDay() {
        assertThatThrownBy(() -> BillingCycles.startingAt(LocalDate.of(2026, 1, 1), 0))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> BillingCycles.startingAt(LocalDate.of(2026, 1, 1), 32))
                .isInstanceOf(BusinessRuleException.class);
    }
}
