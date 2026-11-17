package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries;
import br.com.gestorcobranca.incentive.application.port.out.FactStore;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** O recálculo do tempo logado de um período é só do ator de sistema (decisão 1355). */
class LoggedTimeSyncGuardTest {

    @Test
    void recusaQuemNaoEOAtorDeSistema() {
        WorkforceQueries workforce = mock(WorkforceQueries.class);
        FactStore facts = mock(FactStore.class);
        CurrentActor currentActor = mock(CurrentActor.class);
        UUID tenant = UUID.randomUUID();
        Actor system = Actor.system(tenant, "teste");
        when(currentActor.get()).thenReturn(new Actor(Actor.ActorType.USER, UUID.randomUUID(), tenant, null, "gestor",
                system.permissions(), system.creditorScope()));
        LoggedTimeSync sync = new LoggedTimeSync(workforce, mock(UserDirectory.class), facts, Clock.systemUTC(),
                currentActor);
        assertThatThrownBy(() -> sync.resyncLoggedTime(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(workforce, facts);
    }
}
