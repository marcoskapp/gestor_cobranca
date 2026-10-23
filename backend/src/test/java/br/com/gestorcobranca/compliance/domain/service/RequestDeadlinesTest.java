package br.com.gestorcobranca.compliance.domain.service;

import br.com.gestorcobranca.compliance.domain.model.RequestType;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestDeadlinesTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    @Test
    void fifteenCalendarDaysEndingAtTheEndOfTheLastLocalDay() {
        RequestDeadlines deadlines = new RequestDeadlines(Map.of(), 15, SP);
        // recebida em 03/03 10h (13h UTC) → vence ao fim de 18/03 no fuso de operação (19/03 00h = 03h UTC)
        Instant received = Instant.parse("2026-03-03T13:00:00Z");
        assertThat(deadlines.dueAt(RequestType.ACCESS, received)).isEqualTo(Instant.parse("2026-03-19T03:00:00Z"));
    }

    @Test
    void receptionLateInTheLocalEveningCountsFromTheLocalDate() {
        RequestDeadlines deadlines = new RequestDeadlines(Map.of(), 15, SP);
        // 02h UTC de 04/03 ainda é 03/03 23h em São Paulo
        Instant received = Instant.parse("2026-03-04T02:00:00Z");
        assertThat(deadlines.dueAt(RequestType.CONFIRMATION, received)).isEqualTo(Instant.parse("2026-03-19T03:00:00Z"));
    }

    @Test
    void perTypeDeadlinesOverrideTheDefault() {
        RequestDeadlines deadlines = new RequestDeadlines(Map.of(RequestType.OPPOSITION, 5), 15, SP);
        assertThat(deadlines.daysFor(RequestType.OPPOSITION)).isEqualTo(5);
        assertThat(deadlines.daysFor(RequestType.ACCESS)).isEqualTo(15);
        assertThat(deadlines.dueAt(RequestType.OPPOSITION, Instant.parse("2026-03-03T13:00:00Z")))
                .isEqualTo(Instant.parse("2026-03-09T03:00:00Z"));
        assertThatThrownBy(() -> new RequestDeadlines(Map.of(RequestType.ACCESS, 0), 15, SP))
                .isInstanceOf(BusinessRuleException.class);
    }
}
