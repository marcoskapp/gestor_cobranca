package br.com.gestorcobranca.payment.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Rotas de webhook Pix (tabela global {@code payment.webhook_route}, sem RLS): resolvem tenant e conta
 * a partir do {@code routingKey} antes de existir contexto de tenant.
 */
public interface WebhookRouteDirectory {

    void register(String routingKey, UUID tenantId, UUID pixAccountId, String provider);

    void setActive(String routingKey, boolean active);

    Optional<Route> resolve(String routingKey);

    record Route(String routingKey, UUID tenantId, UUID pixAccountId, String provider, boolean active) {
    }
}
