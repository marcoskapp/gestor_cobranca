package br.com.gestorcobranca.sandbox.infrastructure.config;

import br.com.gestorcobranca.sandbox.infrastructure.time.ShiftableClock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** O relógio deslocável (global) só existe no perfil sandbox com a carga ligada (decisão 1351). */
class DemoClockConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DemoClockConfiguration.class);

    @Test
    void semPerfilSandboxORelogioDeslocavelNaoExiste() {
        runner.withPropertyValues("app.sandbox.seed.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ShiftableClock.class));
    }

    @Test
    void comPerfilSandboxSemCargaORelogioDeslocavelNaoExiste() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("sandbox"))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ShiftableClock.class));
    }

    @Test
    void comPerfilSandboxECargaLigadaORelogioDeslocavelExiste() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("sandbox"))
                .withPropertyValues("app.sandbox.seed.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(ShiftableClock.class));
    }
}
