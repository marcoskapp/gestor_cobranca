package br.com.gestorcobranca.complaint.domain;

import br.com.gestorcobranca.complaint.domain.model.Complaint;
import br.com.gestorcobranca.complaint.domain.model.ComplaintChannel;
import br.com.gestorcobranca.complaint.domain.model.ComplaintDeadlineRule;
import br.com.gestorcobranca.complaint.domain.model.ComplaintOutcome;
import br.com.gestorcobranca.complaint.domain.model.ComplaintSubject;
import br.com.gestorcobranca.complaint.domain.model.DeadlineSource;
import br.com.gestorcobranca.complaint.domain.service.ComplaintDeadlines;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Prazos e ciclo da reclamação (I5). */
class ComplaintRulesTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate RECEIVED = LocalDate.of(2026, 11, 2);

    @Test
    void prazoInformadoPrevaleceDepoisARegraDoCanalDepoisOPadrao() {
        List<ComplaintDeadlineRule> rules = List.of(new ComplaintDeadlineRule(ComplaintChannel.CONSUMIDOR_GOV, 10, 3));
        var informed = ComplaintDeadlines.resolve(ComplaintChannel.CONSUMIDOR_GOV, RECEIVED, RECEIVED.plusDays(5), rules);
        assertThat(informed.dueDate()).isEqualTo(RECEIVED.plusDays(5));
        assertThat(informed.source()).isEqualTo(DeadlineSource.INFORMED);
        var rule = ComplaintDeadlines.resolve(ComplaintChannel.CONSUMIDOR_GOV, RECEIVED, null, rules);
        assertThat(rule.dueDate()).isEqualTo(RECEIVED.plusDays(10));
        assertThat(rule.source()).isEqualTo(DeadlineSource.CHANNEL_RULE);
        var standard = ComplaintDeadlines.resolve(ComplaintChannel.PROCON, RECEIVED, null, rules);
        assertThat(standard.dueDate()).isEqualTo(RECEIVED.plusDays(ComplaintDeadlines.DEFAULT_DAYS));
        assertThat(standard.source()).isEqualTo(DeadlineSource.DEFAULT);
        assertThatThrownBy(() -> ComplaintDeadlines.resolve(ComplaintChannel.PROCON, RECEIVED, RECEIVED.minusDays(1),
                rules)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new ComplaintDeadlineRule(ComplaintChannel.PROCON, 5, 5))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void situacaoDoPrazoERespostaNoPrazo() {
        Complaint c = complaint(RECEIVED.plusDays(10), 3);
        assertThat(c.deadlineState(RECEIVED.plusDays(1))).isEqualTo("ON_TIME");
        assertThat(c.deadlineState(RECEIVED.plusDays(7))).isEqualTo("DUE_SOON");
        assertThat(c.deadlineState(RECEIVED.plusDays(11))).isEqualTo("OVERDUE");
        assertThat(c.markOverdueNotified(RECEIVED.plusDays(11), Instant.now())).isTrue();
        assertThat(c.markOverdueNotified(RECEIVED.plusDays(12), Instant.now())).isFalse();
        // fim do dia do prazo ainda é no prazo (fuso de negócio)
        c.answer("Resposta protocolada", RECEIVED.plusDays(10).atTime(23, 30).atZone(ZONE).toInstant(), null, ZONE);
        assertThat(c.getAnsweredOnTime()).isTrue();
        assertThat(c.deadlineState(RECEIVED.plusDays(20))).isEqualTo("ANSWERED_ON_TIME");
        c.close(ComplaintOutcome.RESOLVED, Instant.now(), null);
        assertThatThrownBy(() -> c.reclassify(null, null, null, ComplaintSubject.OTHER)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void respostaForaDoPrazoEEncerrarExigeResposta() {
        Complaint c = complaint(RECEIVED.plusDays(2), 1);
        assertThatThrownBy(() -> c.close(ComplaintOutcome.RESOLVED, Instant.now(), null))
                .isInstanceOf(RuntimeException.class);
        c.answer("Resposta", RECEIVED.plusDays(3).atStartOfDay(ZONE).plusHours(1).toInstant(), null, ZONE);
        assertThat(c.getAnsweredOnTime()).isFalse();
        assertThat(c.deadlineState(RECEIVED.plusDays(3))).isEqualTo("ANSWERED_LATE");
    }

    @Test
    void casoExigeDevedorETextoObrigatorio() {
        assertThatThrownBy(() -> new Complaint(UUID.randomUUID(), null, UUID.randomUUID(), ComplaintChannel.PROCON, null,
                ComplaintSubject.OTHER, "Texto", Instant.now(), RECEIVED, DeadlineSource.DEFAULT, 3, null))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new Complaint(UUID.randomUUID(), null, null, ComplaintChannel.PROCON, null,
                ComplaintSubject.OTHER, "  ", Instant.now(), RECEIVED, DeadlineSource.DEFAULT, 3, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    private static Complaint complaint(LocalDate due, int warningDays) {
        return new Complaint(UUID.randomUUID(), UUID.randomUUID(), null, ComplaintChannel.PROCON, "P-1",
                ComplaintSubject.ABUSIVE_COLLECTION, "Relato fictício", RECEIVED.atStartOfDay(ZONE).toInstant(), due,
                DeadlineSource.DEFAULT, warningDays, null);
    }
}
