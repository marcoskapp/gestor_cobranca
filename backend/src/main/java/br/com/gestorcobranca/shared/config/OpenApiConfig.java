package br.com.gestorcobranca.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentação OpenAPI ({@code /v3/api-docs}). Em produção a UI fica desabilitada e a
 * especificação só é acessível na rede interna (ver {@code application-prod.yml}).
 * <p>
 * Grupos separados: {@code interna} ({@code /api/**}, JWT de usuário) e os grupos da API pública declarados pelo módulo
 * publicapi ({@code /public-api/v<n>/**}, token de integração) — um contrato nunca mistura os dois.
 */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Gestor de Cobrança API").version("v1")
                        .description("API da plataforma de cobrança e recuperação de crédito"))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }

    @Bean
    GroupedOpenApi internalApi() {
        return GroupedOpenApi.builder().group("interna").displayName("API interna").pathsToMatch("/api/**").build();
    }
}
