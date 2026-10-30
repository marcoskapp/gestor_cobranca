package br.com.gestorcobranca.sandbox.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxSeedGuardTest {

    @Test
    void seedOnlyWithSandboxProfileOutsideProduction() {
        assertThatCode(() -> SandboxSeedGuard.check(false, true, "sandbox")).doesNotThrowAnyException();
        assertThatThrownBy(() -> SandboxSeedGuard.check(true, true, "sandbox"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("prod");
        assertThatThrownBy(() -> SandboxSeedGuard.check(false, false, "sandbox"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("perfil sandbox");
        assertThatThrownBy(() -> SandboxSeedGuard.check(false, true, "producao"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("app.environment");
        assertThatThrownBy(() -> SandboxSeedGuard.check(false, true, null)).isInstanceOf(IllegalStateException.class);
    }
}
