package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.application.port.in.InboxRealtimeUseCases.InboxSignal;
import br.com.gestorcobranca.communication.application.port.in.InboxRealtimeUseCases.Subscription;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Filtro dos avisos em tempo real por assessoria e escopo de credores. */
class InboxChangeHubTest {

    private final AccessScope accessScope = mock(AccessScope.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final InboxChangeHub hub = new InboxChangeHub(accessScope, currentActor, new SimpleMeterRegistry());
    private final UUID tenant = UUID.randomUUID();
    private final UUID creditorA = UUID.randomUUID();
    private final UUID creditorB = UUID.randomUUID();

    private Subscription subscribe(Set<UUID> creditors, List<InboxSignal> sink) {
        CreditorScope scope = creditors == null ? CreditorScope.ALL : CreditorScope.of(creditors);
        when(currentActor.get()).thenReturn(new Actor(Actor.ActorType.USER, UUID.randomUUID(), tenant, UUID.randomUUID(),
                "OP", Set.of(Permission.INBOX_VIEW), scope));
        when(accessScope.hasAllCreditors()).thenReturn(creditors == null);
        when(accessScope.restrictCreditors(any())).thenReturn(creditors == null ? Set.of() : creditors);
        return hub.subscribe(sink::add);
    }

    @Test
    void deliversOnlyWithinTenantAndCreditorScope() {
        List<InboxSignal> all = new ArrayList<>();
        List<InboxSignal> onlyB = new ArrayList<>();
        subscribe(null, all);
        Subscription b = subscribe(Set.of(creditorB), onlyB);
        UUID convA = UUID.randomUUID();
        UUID convB = UUID.randomUUID();
        UUID unidentified = UUID.randomUUID();

        hub.changed(tenant, convA, creditorA);
        hub.changed(tenant, convB, creditorB);
        hub.changed(tenant, unidentified, null);
        hub.changed(UUID.randomUUID(), UUID.randomUUID(), creditorB);

        assertThat(all).extracting(InboxSignal::conversationId).containsExactly(convA, convB, unidentified);
        assertThat(onlyB).extracting(InboxSignal::conversationId).containsExactly(convB);

        hub.resync();
        assertThat(onlyB).last().extracting(InboxSignal::kind).isEqualTo(InboxSignal.RESYNC);

        b.close();
        b.close();
        hub.changed(tenant, convB, creditorB);
        assertThat(onlyB).hasSize(2);
        assertThat(all).hasSize(5);
    }

    @Test
    void failingSubscriberDoesNotAffectOthers() {
        List<InboxSignal> ok = new ArrayList<>();
        when(currentActor.get()).thenReturn(new Actor(Actor.ActorType.USER, UUID.randomUUID(), tenant, UUID.randomUUID(),
                "OP", Set.of(Permission.INBOX_VIEW), CreditorScope.ALL));
        when(accessScope.hasAllCreditors()).thenReturn(true);
        hub.subscribe(s -> {
            throw new IllegalStateException("conexão encerrada");
        });
        hub.subscribe(ok::add);
        hub.changed(tenant, UUID.randomUUID(), creditorA);
        assertThat(ok).hasSize(1);
    }
}
