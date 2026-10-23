package br.com.gestorcobranca.telephony.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Rotas de webhook (tabela global {@code telephony.webhook_route}, sem RLS): resolvem o tenant e a
 * conta a partir do {@code routingKey} antes de existir contexto de tenant.
 */
public interface WebhookRouteDirectory {

    void register(String routingKey, UUID tenantId, UUID accountId, String provider);

    void setActive(String routingKey, boolean active);

    Optional<Route> resolve(String routingKey);

    record Route(String routingKey, UUID tenantId, UUID accountId, String provider, boolean active) {
    }
}
