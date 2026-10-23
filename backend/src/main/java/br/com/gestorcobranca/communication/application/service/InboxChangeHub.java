package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.application.port.in.InboxRealtimeUseCases;
import br.com.gestorcobranca.communication.application.port.out.InboxChangeSink;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Distribui, nesta instância, os avisos de mudança de conversa às assinaturas locais (conexões SSE),
 * aplicando assessoria e escopo de credores capturados na assinatura. O aviso não carrega dados da
 * conversa: o cliente consulta os endpoints normais.
 */
@Component
class InboxChangeHub implements InboxRealtimeUseCases, InboxChangeSink {

    private static final Logger LOG = LoggerFactory.getLogger(InboxChangeHub.class);

    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final MeterRegistry registry;
    private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();

    InboxChangeHub(AccessScope accessScope, CurrentActor currentActor, MeterRegistry registry) {
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.registry = registry;
        Gauge.builder("communication.realtime.subscribers", subscribers, Set::size).register(registry);
    }

    /** @param creditors credores do escopo (nulo = todos) */
    private record Subscriber(UUID tenantId, Set<UUID> creditors, Consumer<InboxSignal> listener) {

        boolean accepts(UUID tenant, UUID creditorId) {
            if (!tenantId.equals(tenant)) {
                return false;
            }
            return creditors == null || (creditorId != null && creditors.contains(creditorId));
        }
    }

    @Override
    public Subscription subscribe(Consumer<InboxSignal> listener) {
        accessScope.requirePermission(Permission.INBOX_VIEW);
        Actor actor = currentActor.get();
        Set<UUID> creditors = accessScope.hasAllCreditors() ? null : Set.copyOf(accessScope.restrictCreditors(null));
        Subscriber subscriber = new Subscriber(actor.tenantId(), creditors, listener);
        subscribers.add(subscriber);
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                subscribers.remove(subscriber);
            }
        };
    }

    @Override
    public void changed(UUID tenantId, UUID conversationId, UUID creditorId) {
        InboxSignal signal = new InboxSignal(InboxSignal.CONVERSATION, conversationId);
        int delivered = 0;
        for (Subscriber s : subscribers) {
            if (s.accepts(tenantId, creditorId) && deliver(s, signal)) {
                delivered++;
            }
        }
        registry.counter("communication.realtime.signals", "kind", "conversation",
                "outcome", delivered > 0 ? "delivered" : "no_subscriber").increment();
    }

    @Override
    public void resync() {
        InboxSignal signal = new InboxSignal(InboxSignal.RESYNC, null);
        subscribers.forEach(s -> deliver(s, signal));
        registry.counter("communication.realtime.signals", "kind", "resync", "outcome", "delivered").increment();
    }

    private boolean deliver(Subscriber s, InboxSignal signal) {
        try {
            s.listener().accept(signal);
            return true;
        } catch (RuntimeException e) {
            // a conexão é encerrada pelo próprio assinante; um assinante com falha não afeta os demais
            LOG.atDebug().setCause(e).log("Falha ao repassar aviso da caixa de entrada");
            return false;
        }
    }
}
