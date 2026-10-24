package br.com.gestorcobranca.portal.infrastructure.config;

import br.com.gestorcobranca.shared.security.PublicEndpoint;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * Endpoints públicos do portal (sem token): marca e logotipo do portal e o fluxo de acesso (pedido de
 * código, verificação, resgate do link), protegidos por limite por IP, bloqueio progressivo e respostas
 * idênticas. O restante de {@code /api/portal/v1/**} exige o token do portal (ator {@code DEBTOR}).
 */
@Configuration
class PortalWebConfig {

    @Bean
    PublicEndpoint portalSite() {
        return new PublicEndpoint(HttpMethod.GET, ApiPaths.PORTAL + "/sites/*", "Marca pública do portal do credor");
    }

    @Bean
    PublicEndpoint portalLogo() {
        return new PublicEndpoint(HttpMethod.GET, ApiPaths.PORTAL + "/sites/*/logo", "Logotipo público do portal");
    }

    @Bean
    PublicEndpoint portalAccess() {
        return new PublicEndpoint(HttpMethod.POST, ApiPaths.PORTAL + "/sites/*/access/**",
                "Acesso do devedor ao portal (limite por IP, bloqueio progressivo, anti-enumeração)");
    }
}
