package br.com.gestorcobranca.helpdesk.domain;

import br.com.gestorcobranca.helpdesk.domain.model.SupportTicket;
import br.com.gestorcobranca.helpdesk.domain.model.TicketStatus;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ciclo do chamado: confirmação única, abertura interrompida, falha com nova tentativa, expiração e duplicado. */
class SupportTicketTest {

    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");

    private SupportTicket ticket() {
        return new SupportTicket(UUID.randomUUID(), UUID.randomUUID(), "[Suporte] Erro", "## Resumo\n\nerro", "abc",
                "collection.queue", "dono/repo", T0, Duration.ofHours(24));
    }

    @Test
    void confirmationIsSerializedAndRecoversAnInterruptedCreation() {
        SupportTicket t = ticket();
        assertThat(t.startCreation(T0.plusSeconds(10))).isFalse();
        assertThat(t.getStatus()).isEqualTo(TicketStatus.CREATING);
        // segunda confirmação enquanto a primeira está em andamento
        assertThatThrownBy(() -> t.startCreation(T0.plusSeconds(20))).isInstanceOf(ConflictException.class)
                .hasMessageContaining("sendo aberto");
        // abertura interrompida (instância caiu): depois do prazo, nova tentativa procura o marcador
        assertThat(t.startCreation(T0.plus(SupportTicket.CREATING_STALE_AFTER).plusSeconds(11))).isTrue();
        t.opened(42, "https://github.com/dono/repo/issues/42", T0.plusSeconds(200));
        assertThat(t.getStatus()).isEqualTo(TicketStatus.OPENED);
        assertThat(t.getIssueNumber()).isEqualTo(42);
        assertThat(t.marker()).isEqualTo("gestor-cobranca:helpdesk-ticket:" + t.getId());
        assertThatThrownBy(() -> t.startCreation(T0.plusSeconds(300))).isInstanceOf(ConflictException.class);
    }

    @Test
    void failureAllowsRetryThatLooksForTheMarker() {
        SupportTicket t = ticket();
        t.startCreation(T0);
        t.failed("helpdesk.ticket.unavailable");
        assertThat(t.getStatus()).isEqualTo(TicketStatus.FAILED);
        assertThat(t.getErrorCode()).isEqualTo("helpdesk.ticket.unavailable");
        assertThat(t.startCreation(T0.plusSeconds(5))).isTrue();
        assertThat(t.getAttempts()).isEqualTo(2);
        assertThat(t.getErrorCode()).isNull();
    }

    @Test
    void draftExpiresAndCannotBeConfirmedAfterwards() {
        SupportTicket t = ticket();
        assertThatThrownBy(() -> t.startCreation(T0.plus(Duration.ofHours(25)))).isInstanceOf(ConflictException.class)
                .hasMessageContaining("expirou");
        t.expire(T0.plus(Duration.ofHours(25)));
        assertThat(t.getStatus()).isEqualTo(TicketStatus.EXPIRED);
        assertThatThrownBy(t::cancel).isInstanceOf(ConflictException.class);
    }

    @Test
    void duplicatePointsToTheExistingIssue() {
        SupportTicket first = ticket();
        first.startCreation(T0);
        first.opened(7, "https://github.com/dono/repo/issues/7", T0);
        SupportTicket second = ticket();
        second.startCreation(T0.plusSeconds(60));
        second.duplicateOf(first, T0.plusSeconds(61));
        assertThat(second.getStatus()).isEqualTo(TicketStatus.DUPLICATE);
        assertThat(second.getIssueNumber()).isEqualTo(7);
        assertThat(second.getDuplicateOfId()).isEqualTo(first.getId());
    }
}
