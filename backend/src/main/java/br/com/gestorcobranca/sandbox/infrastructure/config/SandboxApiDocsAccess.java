package br.com.gestorcobranca.sandbox.infrastructure.config;

import br.com.gestorcobranca.shared.security.PublicEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;

/**
 * Sandbox de demonstração (decisão 643, aprovada pelo responsável): interface do Swagger e especificações
 * {@code /v3/api-docs} de todos os grupos abertas no navegador, sem login. Só existe com o perfil {@code sandbox}
 * <strong>e</strong> {@code app.sandbox.api-docs.public=true} (definida em {@code application-sandbox.yml}), depois da
 * {@link SandboxProfileGuard} (que recusa o perfil {@code prod}). Nos demais ambientes nada muda: a documentação
 * interna continua exigindo autenticação (e desligada em produção). Só leitura (GET); as chamadas feitas pela
 * interface continuam exigindo o token de usuário.
 */
@Configuration(proxyBeanMethods = false)
@Profile("sandbox")
@ConditionalOnProperty(prefix = "app.sandbox.api-docs", name = "public", havingValue = "true")
class SandboxApiDocsAccess {

    private static final Logger LOG = LoggerFactory.getLogger(SandboxApiDocsAccess.class);
    private static final String REASON = "Sandbox: documentação da API aberta (decisão 643)";

    SandboxApiDocsAccess(SandboxProfileGuard guard) {
        LOG.warn("Documentação da API (Swagger e /v3/api-docs) aberta sem login — somente no sandbox de demonstração");
    }

    @Bean
    PublicEndpoint sandboxApiDocs() {
        return new PublicEndpoint(HttpMethod.GET, "/v3/api-docs/**", REASON);
    }

    @Bean
    PublicEndpoint sandboxApiDocsRoot() {
        return new PublicEndpoint(HttpMethod.GET, "/v3/api-docs", REASON);
    }

    @Bean
    PublicEndpoint sandboxSwaggerUi() {
        return new PublicEndpoint(HttpMethod.GET, "/swagger-ui/**", REASON);
    }

    @Bean
    PublicEndpoint sandboxSwaggerUiPage() {
        return new PublicEndpoint(HttpMethod.GET, "/swagger-ui.html", REASON);
    }
}
