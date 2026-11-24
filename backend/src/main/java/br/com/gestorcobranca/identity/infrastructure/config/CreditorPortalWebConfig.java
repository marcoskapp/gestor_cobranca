package br.com.gestorcobranca.identity.infrastructure.config;

import br.com.gestorcobranca.shared.security.PublicEndpoint;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.util.List;

/**
 * Rotas públicas do portal do credor (ADR 0011, RS-17): padrões exatos de autenticação e a marca da assessoria — nunca
 * {@code /api/creditor/v1/**}. Todo o resto do portal exige o ator {@code CREDITOR}.
 */
@Configuration
class CreditorPortalWebConfig {

    static final List<String> PUBLIC_AUTH_POST = List.of("login", "mfa", "password/expired", "activation/start",
            "activation/complete", "refresh", "password/forgot", "password/reset");

    @Bean
    PublicEndpoint creditorSite() {
        return new PublicEndpoint(HttpMethod.GET, ApiPaths.CREDITOR + "/sites/*", "Marca da assessoria na entrada do portal do credor");
    }

    @Bean
    PublicEndpoint creditorLogin() {
        return auth("login");
    }

    @Bean
    PublicEndpoint creditorMfa() {
        return auth("mfa");
    }

    @Bean
    PublicEndpoint creditorExpiredPassword() {
        return auth("password/expired");
    }

    @Bean
    PublicEndpoint creditorActivationStart() {
        return auth("activation/start");
    }

    @Bean
    PublicEndpoint creditorActivationComplete() {
        return auth("activation/complete");
    }

    @Bean
    PublicEndpoint creditorRefresh() {
        return auth("refresh");
    }

    @Bean
    PublicEndpoint creditorForgotPassword() {
        return auth("password/forgot");
    }

    @Bean
    PublicEndpoint creditorResetPassword() {
        return auth("password/reset");
    }

    private static PublicEndpoint auth(String path) {
        return new PublicEndpoint(HttpMethod.POST, ApiPaths.CREDITOR + "/auth/" + path,
                "Autenticação do portal do credor (limite por IP e por conta, Origin obrigatório)");
    }
}
