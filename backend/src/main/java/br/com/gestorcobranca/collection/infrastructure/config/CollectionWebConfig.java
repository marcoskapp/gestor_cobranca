package br.com.gestorcobranca.collection.infrastructure.config;

import br.com.gestorcobranca.shared.security.PublicEndpoint;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** Endpoints públicos do módulo collection. */
@Configuration
class CollectionWebConfig {

    @Bean
    PublicEndpoint disputeEvidenceLinkEndpoint() {
        return new PublicEndpoint(HttpMethod.GET, ApiPaths.V1 + "/dispute-files/*",
                "Evidências de contestação enviadas ao credor por link seguro (token de 256 bits, validade e limite)");
    }
}
