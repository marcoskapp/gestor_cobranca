package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.portal.application.port.out.PortalRouteDirectory;
import br.com.gestorcobranca.portal.domain.model.PortalConfiguration;
import br.com.gestorcobranca.portal.domain.repository.PortalConfigurationRepository;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * Resolução do portal público pelo endereço ({@code slug}): rota global → assessoria e configuração;
 * a configuração é lida no tenant (RLS). Portal inexistente, inativo ou desabilitado é sempre o mesmo
 * 404 {@code portal_site}.
 */
@Component
class PortalSites {

    static final String RESOURCE = "portal_site";

    record Site(String slug, UUID tenantId, UUID configurationId) {
    }

    private final PortalRouteDirectory routes;
    private final PortalConfigurationRepository configurations;

    PortalSites(PortalRouteDirectory routes, PortalConfigurationRepository configurations) {
        this.routes = routes;
        this.configurations = configurations;
    }

    /** Sem tenant: só a rota global. */
    Site resolve(String slug) {
        String normalized = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 60) {
            throw new ResourceNotFoundException(RESOURCE, slug);
        }
        return routes.resolve(normalized).filter(PortalRouteDirectory.Route::active)
                .map(r -> new Site(r.slug(), r.tenantId(), r.configurationId()))
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, normalized));
    }

    /** No tenant do portal: configuração habilitada. */
    PortalConfiguration enabled(Site site) {
        return configurations.findById(site.configurationId()).filter(PortalConfiguration::isEnabled)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, site.slug()));
    }
}
