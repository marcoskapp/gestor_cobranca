package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A rotina diária sob demanda é só do ator de sistema (decisão 1355). */
class NegotiationSchedulingServiceTest {

    private final NegotiationJobs jobs = mock(NegotiationJobs.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final NegotiationSchedulingService service = new NegotiationSchedulingService(jobs, currentActor);
    private final UUID tenant = UUID.randomUUID();

    @Test
    void recusaQuemNaoEOAtorDeSistema() {
        Actor system = Actor.system(tenant, "teste");
        when(currentActor.get()).thenReturn(new Actor(Actor.ActorType.USER, UUID.randomUUID(), tenant, null, "gestor",
                system.permissions(), system.creditorScope()));
        assertThatThrownBy(service::runDailyMaintenance).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jobs);
    }

    @Test
    void atorDeSistemaRodaNaPropriaAssessoria() {
        when(currentActor.get()).thenReturn(Actor.system(tenant, "teste"));
        service.runDailyMaintenance();
        verify(jobs).runDaily(tenant);
    }
}
