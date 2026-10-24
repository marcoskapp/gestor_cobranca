package br.com.gestorcobranca.assistant.infrastructure.config;

import br.com.gestorcobranca.assistant.application.AssistantProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantSandboxGuardTest {

    private static AssistantProperties sandbox(boolean enabled) {
        return new AssistantProperties(null, null, null, null, null, null,
                new AssistantProperties.Sandbox(enabled, null, null, null));
    }

    @Test
    void sandboxModelIsRefusedInProduction() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new AssistantSandboxGuard(sandbox(true), prod)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new AssistantSandboxGuard(sandbox(false), prod)).doesNotThrowAnyException();
        assertThatCode(() -> new AssistantSandboxGuard(sandbox(true), new MockEnvironment())).doesNotThrowAnyException();
    }
}
