package br.com.gestorcobranca.telephony.infrastructure.persistence;

import br.com.gestorcobranca.telephony.application.port.out.WebhookRouteDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Rotas de webhook em {@code telephony.webhook_route} (tabela global, sem RLS: consultada antes de
 * haver tenant). Contém apenas a chave aleatória e ids — o segredo fica na conta, protegida por RLS
 * e criptografada.
 */
@Component
class JdbcTelephonyWebhookRoutes implements WebhookRouteDirectory {

    private final JdbcTemplate jdbc;

    JdbcTelephonyWebhookRoutes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void register(String routingKey, UUID tenantId, UUID accountId, String provider) {
        jdbc.update("""
                insert into telephony.webhook_route (routing_key, tenant_id, provider_account_id, provider, active, created_at)
                values (?, ?, ?, ?, true, now())
                """, routingKey, tenantId, accountId, provider);
    }

    @Override
    public void setActive(String routingKey, boolean active) {
        jdbc.update("update telephony.webhook_route set active = ? where routing_key = ?", active, routingKey);
    }

    @Override
    public Optional<Route> resolve(String routingKey) {
        return jdbc.query("""
                        select routing_key, tenant_id, provider_account_id, provider, active
                          from telephony.webhook_route where routing_key = ?
                        """,
                (rs, i) -> new Route(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getBoolean(5)),
                routingKey).stream().findFirst();
    }
}
