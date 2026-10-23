package br.com.gestorcobranca.communication.infrastructure.config;

import br.com.gestorcobranca.communication.application.CommunicationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Impede que o provedor simulado seja habilitado em produção: com
 * {@code app.communication.sandbox.enabled=true} e perfil {@code prod}, a aplicação não sobe.
 */
@Component
class SandboxGuard {

    private static final Logger LOG = LoggerFactory.getLogger(SandboxGuard.class);

    SandboxGuard(CommunicationProperties properties, Environment environment) {
        if (properties.sandbox().enabled()) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("app.communication.sandbox.enabled não é permitido no perfil prod");
            }
            LOG.warn("Provedor de comunicação simulado (sandbox) habilitado — somente para ambiente local/teste");
        }
    }
}
