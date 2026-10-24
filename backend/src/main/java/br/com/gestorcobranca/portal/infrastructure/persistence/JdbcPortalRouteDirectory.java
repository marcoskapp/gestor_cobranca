package br.com.gestorcobranca.portal.infrastructure.persistence;

import br.com.gestorcobranca.portal.application.port.out.PortalRouteDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Rotas públicas em {@code portal.portal_route} (tabela global, sem RLS: consultada antes de haver
 * tenant). Só o endereço e ids; a configuração em si fica protegida por RLS.
 */
@Component
class JdbcPortalRouteDirectory implements PortalRouteDirectory {

    private final JdbcTemplate jdbc;

    JdbcPortalRouteDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(String slug, UUID tenantId, UUID configurationId, boolean active) {
        // troca de endereço: a linha da configuração muda de chave (índice único por configuração)
        int updated = jdbc.update("""
                update portal.portal_route set slug = ?, active = ?, updated_at = now()
                 where configuration_id = ? and tenant_id = ?
                """, slug, active, configurationId, tenantId);
        if (updated == 0) {
            jdbc.update("""
                    insert into portal.portal_route (slug, tenant_id, configuration_id, active, updated_at)
                    values (?, ?, ?, ?, now())
                    """, slug, tenantId, configurationId, active);
        }
    }

    @Override
    public Optional<Route> resolve(String slug) {
        return jdbc.query("select slug, tenant_id, configuration_id, active from portal.portal_route where slug = ?",
                (rs, i) -> new Route(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getBoolean(4)), slug).stream().findFirst();
    }

    @Override
    public boolean slugTaken(String slug, UUID exceptConfigurationId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from portal.portal_route where slug = ? and (?::uuid is null or configuration_id <> ?::uuid)
                """, Integer.class, slug, exceptConfigurationId, exceptConfigurationId);
        return count != null && count > 0;
    }
}
