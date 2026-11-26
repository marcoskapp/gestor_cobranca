package br.com.gestorcobranca.settlement.domain.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Prazo do demonstrativo enviado (decisão do responsável de 26/11/2026): envio + N dias corridos, só lembrete. */
class StatementResponseDeadlineTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    @Test
    void contaDiasCorridosAPartirDaDataDoEnvioNoFusoDeNegocio() {
        // 02:30 UTC de 01/12 ainda é 30/11 em São Paulo: o prazo conta do dia do envio na operação
        assertThat(StatementResponseDeadline.dueOn(Instant.parse("2026-12-01T02:30:00Z"), ZONE, 10))
                .isEqualTo(LocalDate.of(2026, 12, 10));
        assertThat(StatementResponseDeadline.dueOn(Instant.parse("2026-11-26T15:00:00Z"), ZONE,
                StatementResponseDeadline.DEFAULT_DAYS)).isEqualTo(LocalDate.of(2026, 12, 6));
        // fim de semana e feriado contam (dias corridos)
        assertThat(StatementResponseDeadline.dueOn(Instant.parse("2026-12-24T12:00:00Z"), ZONE, 1))
                .isEqualTo(LocalDate.of(2026, 12, 25));
    }

    @Test
    void vencidoSoDepoisDoDiaDoPrazo() {
        LocalDate due = LocalDate.of(2026, 12, 6);
        assertThat(StatementResponseDeadline.overdue(due, LocalDate.of(2026, 12, 6))).isFalse();
        assertThat(StatementResponseDeadline.overdue(due, LocalDate.of(2026, 12, 7))).isTrue();
        assertThat(StatementResponseDeadline.overdue(null, LocalDate.of(2026, 12, 7))).isFalse();
    }

    @Test
    void prazoForaDosLimitesERecusado() {
        Instant sent = Instant.parse("2026-11-26T15:00:00Z");
        assertThatThrownBy(() -> StatementResponseDeadline.dueOn(sent, ZONE, 0)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> StatementResponseDeadline.dueOn(sent, ZONE, 91)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> StatementResponseDeadline.dueOn(null, ZONE, 10)).isInstanceOf(BusinessRuleException.class);
    }
}
