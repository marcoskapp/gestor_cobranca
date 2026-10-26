package br.com.gestorcobranca.helpdesk.infrastructure.config;

import br.com.gestorcobranca.helpdesk.application.HelpdeskProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guarda de configuração do provedor do chat: um por instância e simulado nunca no {@code prod}. */
class HelpdeskProviderGuardTest {

    private static HelpdeskProperties sandbox(boolean enabled) {
        return new HelpdeskProperties(null, null, null, null, new HelpdeskProperties.Sandbox(enabled, null, null, null));
    }

    @Test
    void sandboxAndBedrockAreMutuallyExclusive() {
        MockEnvironment env = new MockEnvironment().withProperty("app.helpdesk.bedrock.enabled", "true");
        assertThatThrownBy(() -> new HelpdeskProviderGuard(sandbox(true), env))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mutuamente exclusivos");
    }

    @Test
    void sandboxIsRefusedInProduction() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThatThrownBy(() -> new HelpdeskProviderGuard(sandbox(true), env))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("prod");
    }

    @Test
    void noProviderOrOneProviderStarts() {
        assertThatCode(() -> new HelpdeskProviderGuard(sandbox(false), new MockEnvironment())).doesNotThrowAnyException();
        assertThatCode(() -> new HelpdeskProviderGuard(sandbox(false),
                new MockEnvironment().withProperty("app.helpdesk.bedrock.enabled", "true"))).doesNotThrowAnyException();
        assertThatCode(() -> new HelpdeskProviderGuard(sandbox(true), new MockEnvironment())).doesNotThrowAnyException();
    }
}
