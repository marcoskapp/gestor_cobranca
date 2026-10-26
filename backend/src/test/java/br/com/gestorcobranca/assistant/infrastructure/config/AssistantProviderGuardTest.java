package br.com.gestorcobranca.assistant.infrastructure.config;

import br.com.gestorcobranca.assistant.application.AssistantProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantProviderGuardTest {

    private static AssistantProperties sandbox(boolean enabled) {
        return new AssistantProperties(null, null, null, null, null, null,
                new AssistantProperties.Sandbox(enabled, null, null, null));
    }

    private static MockEnvironment prod() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        return prod;
    }

    @Test
    void sandboxModelIsRefusedInProduction() {
        assertThatThrownBy(() -> new AssistantProviderGuard(sandbox(true), prod())).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new AssistantProviderGuard(sandbox(false), prod())).doesNotThrowAnyException();
        assertThatCode(() -> new AssistantProviderGuard(sandbox(true), new MockEnvironment())).doesNotThrowAnyException();
    }

    @Test
    void sandboxAndBedrockAreMutuallyExclusive() {
        MockEnvironment both = new MockEnvironment().withProperty("app.assistant.bedrock.enabled", "true");
        assertThatThrownBy(() -> new AssistantProviderGuard(sandbox(true), both))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mutuamente exclusivos");
        MockEnvironment bedrockInProd = prod().withProperty("app.assistant.bedrock.enabled", "true");
        assertThatCode(() -> new AssistantProviderGuard(sandbox(false), bedrockInProd)).doesNotThrowAnyException();
    }
}
