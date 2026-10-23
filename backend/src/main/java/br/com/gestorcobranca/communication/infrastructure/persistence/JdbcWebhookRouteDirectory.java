package br.com.gestorcobranca.communication.infrastructure.persistence;

import br.com.gestorcobranca.communication.application.port.out.WebhookRouteDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Rotas de webhook em {@code communication.webhook_route} (tabela global, sem RLS: consultada antes
 * de haver tenant). Contém apenas a chave aleatória e ids — o segredo de assinatura fica na conta,
 * protegida por RLS e criptografada.
 */
@Component
class JdbcWebhookRouteDirectory implements WebhookRouteDirectory {

    private final JdbcTemplate jdbc;

    JdbcWebhookRouteDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void register(String routingKey, UUID tenantId, UUID channelAccountId, String provider) {
        jdbc.update("""
                insert into communication.webhook_route (routing_key, tenant_id, channel_account_id, provider, active, created_at)
                values (?, ?, ?, ?, true, now())
                """, routingKey, tenantId, channelAccountId, provider);
    }

    @Override
    public void setActive(String routingKey, boolean active) {
        jdbc.update("update communication.webhook_route set active = ? where routing_key = ?", active, routingKey);
    }

    @Override
    public Optional<Route> resolve(String routingKey) {
        return jdbc.query("""
                        select routing_key, tenant_id, channel_account_id, provider, active
                          from communication.webhook_route where routing_key = ?
                        """,
                (rs, i) -> new Route(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getBoolean(5)),
                routingKey).stream().findFirst();
    }
}
