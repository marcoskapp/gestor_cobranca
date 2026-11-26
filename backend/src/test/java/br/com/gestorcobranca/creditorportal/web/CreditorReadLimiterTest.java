package br.com.gestorcobranca.creditorportal.web;

import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Limite das leituras do Início e da atividade do portal do credor (fatia 7): 30 por minuto por pessoa e operação. */
class CreditorReadLimiterTest {

    @Test
    void trigesimaPrimeiraLeituraNoMesmoMinutoDa429ComRetryAfterEOutraOperacaoTemJanelaPropria() {
        MutableClock clock = new MutableClock();
        clock.set(Instant.parse("2026-11-26T12:00:20Z"));
        UUID person = UUID.randomUUID();
        Actor creditor = Actor.creditor(UUID.randomUUID(), person, UUID.randomUUID(), UUID.randomUUID(),
                Set.of(Permission.CREDITOR_PORTAL_OVERVIEW));
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.actorId()).thenReturn(person);
        when(currentActor.get()).thenReturn(creditor);
        AccessLogger accessLogger = mock(AccessLogger.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CreditorReadLimiter limiter = new CreditorReadLimiter(currentActor, accessLogger, registry, clock);

        for (int i = 0; i < CreditorReadLimiter.READ_LIMIT_PER_MINUTE; i++) {
            limiter.acquire("overview", CreditorReadLimiter.READ_LIMIT_PER_MINUTE, new MockHttpServletResponse());
        }
        // a atividade conta numa janela própria
        limiter.acquire("activity", CreditorReadLimiter.READ_LIMIT_PER_MINUTE, new MockHttpServletResponse());
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        assertThatThrownBy(() -> limiter.acquire("overview", CreditorReadLimiter.READ_LIMIT_PER_MINUTE, rejected))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("429");
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("40");
        // segunda recusa na mesma janela: a métrica conta, o log de acesso não se repete
        assertThatThrownBy(() -> limiter.acquire("overview", CreditorReadLimiter.READ_LIMIT_PER_MINUTE,
                new MockHttpServletResponse())).isInstanceOf(ResponseStatusException.class);
        assertThat(registry.counter("creditor_portal.reads.rejected", "operation", "overview", "reason", "rate_limited")
                .count()).isEqualTo(2);
        ArgumentCaptor<AccessEvent> event = ArgumentCaptor.forClass(AccessEvent.class);
        verify(accessLogger).log(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AccessEventType.RATE_LIMITED);
        assertThat(event.getValue().details()).containsEntry("scope", "creditor_read").containsEntry("operation", "overview");

        clock.set(Instant.parse("2026-11-26T12:01:00Z"));
        limiter.acquire("overview", CreditorReadLimiter.READ_LIMIT_PER_MINUTE, new MockHttpServletResponse());
    }
}
