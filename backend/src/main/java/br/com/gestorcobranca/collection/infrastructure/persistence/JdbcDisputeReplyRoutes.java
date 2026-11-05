package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.application.port.out.DisputeReplyRoutes;
import br.com.gestorcobranca.shared.domain.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Rotas dos endereços de resposta em {@code collection.dispute_reply_route} e e-mails sem rota em
 * {@code collection.inbound_mail_unrouted} (tabelas globais, sem RLS: consultadas antes de haver assessoria).
 */
@Component
class JdbcDisputeReplyRoutes implements DisputeReplyRoutes {

    private final JdbcTemplate jdbc;

    JdbcDisputeReplyRoutes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void register(String tokenHash, UUID tenantId, UUID requestId, Instant expiresAt) {
        jdbc.update("""
                insert into collection.dispute_reply_route (token_hash, tenant_id, request_id, expires_at, created_at)
                values (?, ?, ?, ?, now())
                """, tokenHash, tenantId, requestId, Timestamp.from(expiresAt));
    }

    @Override
    public Optional<Route> find(String tokenHash) {
        return jdbc.query("select tenant_id, request_id, expires_at from collection.dispute_reply_route where token_hash = ?",
                (rs, n) -> new Route(rs.getObject("tenant_id", UUID.class), rs.getObject("request_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant()), tokenHash).stream().findFirst();
    }

    @Override
    public boolean recordUnrouted(String providerMessageId, Instant receivedAt, String rawKey, long size, String reason) {
        return jdbc.update("""
                insert into collection.inbound_mail_unrouted (id, received_at, provider_message_id, raw_key, size_bytes,
                                                              reason, created_at)
                values (?, ?, ?, ?, ?, ?, now())
                on conflict (provider_message_id) do nothing
                """, Ids.newId(), Timestamp.from(receivedAt), providerMessageId, rawKey, size, reason) > 0;
    }
}
