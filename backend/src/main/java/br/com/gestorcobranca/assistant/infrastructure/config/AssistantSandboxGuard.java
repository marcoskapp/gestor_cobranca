package br.com.gestorcobranca.assistant.infrastructure.config;

import br.com.gestorcobranca.assistant.application.AssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Impede que o modelo de linguagem simulado seja habilitado em produção: com
 * {@code app.assistant.sandbox.enabled=true} e perfil {@code prod}, a aplicação não sobe.
 */
@Component
class AssistantSandboxGuard {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantSandboxGuard.class);

    AssistantSandboxGuard(AssistantProperties properties, Environment environment) {
        if (properties.sandbox().enabled()) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("app.assistant.sandbox.enabled não é permitido no perfil prod");
            }
            LOG.warn("Modelo de linguagem simulado (sandbox) do assistente habilitado — somente para ambiente local/teste");
        }
    }
}
