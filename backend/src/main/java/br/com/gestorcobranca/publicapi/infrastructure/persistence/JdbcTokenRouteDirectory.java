package br.com.gestorcobranca.publicapi.infrastructure.persistence;

import br.com.gestorcobranca.publicapi.application.port.out.TokenRouteDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Rotas dos tokens em {@code publicapi.token_route} (tabela global, sem RLS: consultada antes de haver tenant). Só o
 * prefixo e ids — o hash fica no token, protegido por RLS.
 */
@Component
class JdbcTokenRouteDirectory implements TokenRouteDirectory {

    private final JdbcTemplate jdbc;

    JdbcTokenRouteDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void register(String prefix, UUID tenantId, UUID tokenId) {
        jdbc.update("insert into publicapi.token_route (prefix, tenant_id, token_id, created_at) values (?, ?, ?, now())",
                prefix, tenantId, tokenId);
    }

    @Override
    public Optional<Route> resolve(String prefix) {
        return jdbc.query("select tenant_id, token_id from publicapi.token_route where prefix = ?",
                (rs, i) -> new Route(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), prefix).stream().findFirst();
    }
}
