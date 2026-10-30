package br.com.gestorcobranca.sandbox.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Impede que a carga de dados fictícios seja ligada por acidente: com {@code app.sandbox.seed.enabled=true}, a
 * aplicação só sobe com o perfil {@code sandbox} ativo, sem o perfil {@code prod} e com {@code app.environment=sandbox}.
 * Independe do perfil (é carregado só pela flag), então protege inclusive quem ligar a flag em outro perfil.
 */
@Component
@ConditionalOnProperty(prefix = "app.sandbox.seed", name = "enabled", havingValue = "true")
class SandboxSeedGuard {

    private static final Logger LOG = LoggerFactory.getLogger(SandboxSeedGuard.class);

    SandboxSeedGuard(Environment environment, @Value("${app.environment:}") String appEnvironment) {
        check(environment.acceptsProfiles(Profiles.of("prod")), environment.acceptsProfiles(Profiles.of("sandbox")),
                appEnvironment);
        LOG.warn("Carga de dados FICTÍCIOS do sandbox habilitada — somente para demonstração local");
    }

    static void check(boolean prodProfile, boolean sandboxProfile, String appEnvironment) {
        if (prodProfile) {
            throw new IllegalStateException("app.sandbox.seed.enabled não é permitido no perfil prod");
        }
        if (!sandboxProfile) {
            throw new IllegalStateException("app.sandbox.seed.enabled exige o perfil sandbox");
        }
        if (!"sandbox".equals(appEnvironment)) {
            throw new IllegalStateException("app.sandbox.seed.enabled exige app.environment=sandbox (APP_ENV)");
        }
    }
}
