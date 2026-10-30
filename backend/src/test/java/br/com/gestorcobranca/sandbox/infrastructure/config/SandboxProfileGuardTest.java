package br.com.gestorcobranca.sandbox.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxProfileGuardTest {

    @Test
    void sandboxProfileOnlyOutsideProductionAndInTheSandboxEnvironment() {
        assertThatCode(() -> SandboxProfileGuard.check(false, "sandbox")).doesNotThrowAnyException();
        assertThatThrownBy(() -> SandboxProfileGuard.check(true, "sandbox"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("prod");
        assertThatThrownBy(() -> SandboxProfileGuard.check(false, "producao"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("app.environment");
        assertThatThrownBy(() -> SandboxProfileGuard.check(false, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWhenProdIsActiveTogetherWithSandbox() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("sandbox", "prod");
        assertThatThrownBy(() -> new SandboxProfileGuard(env, "sandbox")).isInstanceOf(IllegalStateException.class);
        MockEnvironment sandbox = new MockEnvironment();
        sandbox.setActiveProfiles("sandbox");
        assertThatCode(() -> new SandboxProfileGuard(sandbox, "sandbox")).doesNotThrowAnyException();
    }
}
