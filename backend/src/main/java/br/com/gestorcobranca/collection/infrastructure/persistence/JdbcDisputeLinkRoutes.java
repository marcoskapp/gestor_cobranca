package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.application.port.out.DisputeLinkRoutes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Rotas dos links de evidências em {@code collection.dispute_link_route} (tabela global, sem RLS: consultada antes de
 * haver assessoria). Só o hash do token, ids e a validade — o conteúdo é lido depois, no tenant, sob RLS.
 */
@Component
class JdbcDisputeLinkRoutes implements DisputeLinkRoutes {

    private final JdbcTemplate jdbc;

    JdbcDisputeLinkRoutes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void register(String tokenHash, UUID tenantId, UUID requestId, Instant expiresAt) {
        jdbc.update("""
                insert into collection.dispute_link_route (token_hash, tenant_id, request_id, expires_at, created_at)
                values (?, ?, ?, ?, now())
                """, tokenHash, tenantId, requestId, Timestamp.from(expiresAt));
    }

    @Override
    public Optional<Route> find(String tokenHash) {
        return jdbc.query("select tenant_id, request_id, expires_at from collection.dispute_link_route where token_hash = ?",
                (rs, n) -> new Route(rs.getObject("tenant_id", UUID.class), rs.getObject("request_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant()), tokenHash).stream().findFirst();
    }

    @Override
    public int purgeExpired(Instant before) {
        return jdbc.update("delete from collection.dispute_link_route where expires_at < ?", Timestamp.from(before));
    }
}
