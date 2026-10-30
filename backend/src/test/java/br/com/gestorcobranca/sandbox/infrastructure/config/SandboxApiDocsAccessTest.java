package br.com.gestorcobranca.sandbox.infrastructure.config;

import br.com.gestorcobranca.shared.security.PublicEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * As liberações da documentação só existem com o perfil {@code sandbox} e a flag, e passam pela guarda (que recusa
 * {@code prod}). O efeito HTTP (aberto no sandbox, protegido fora) é coberto por {@code SandboxApiDocsAccessIT}.
 */
class SandboxApiDocsAccessTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SandboxProfileGuard.class, SandboxApiDocsAccess.class);

    @Test
    void openOnlyWithSandboxProfileAndFlag() {
        runner.withPropertyValues("spring.profiles.active=sandbox", "app.environment=sandbox",
                        "app.sandbox.api-docs.public=true")
                .run(ctx -> assertThat(ctx.getBeansOfType(PublicEndpoint.class).values())
                        .extracting(PublicEndpoint::pattern)
                        .containsExactlyInAnyOrder("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html"));
    }

    @Test
    void closedWithoutTheSandboxProfileOrWithoutTheFlag() {
        runner.withPropertyValues("app.environment=sandbox", "app.sandbox.api-docs.public=true")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(PublicEndpoint.class));
        runner.withPropertyValues("spring.profiles.active=local", "app.sandbox.api-docs.public=true")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(PublicEndpoint.class));
        runner.withPropertyValues("spring.profiles.active=sandbox", "app.environment=sandbox")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(PublicEndpoint.class));
    }

    @Test
    void refusesSandboxCombinedWithProd() {
        runner.withPropertyValues("spring.profiles.active=sandbox,prod", "app.environment=sandbox",
                        "app.sandbox.api-docs.public=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
