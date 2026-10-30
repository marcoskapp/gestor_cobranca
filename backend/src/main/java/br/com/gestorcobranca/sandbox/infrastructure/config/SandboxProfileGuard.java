package br.com.gestorcobranca.sandbox.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Guarda do perfil {@code sandbox}: a aplicação não sobe com {@code sandbox} junto com {@code prod} nem fora do
 * ambiente {@code sandbox} ({@code APP_ENV}). Protege tudo o que só o perfil sandbox liga (documentação aberta da
 * API, provedores simulados configurados em {@code application-sandbox.yml}); a carga de dados fictícios tem ainda a
 * própria guarda ({@link SandboxSeedGuard}).
 */
@Component
@Profile("sandbox")
public class SandboxProfileGuard {

    SandboxProfileGuard(Environment environment, @Value("${app.environment:}") String appEnvironment) {
        check(environment.acceptsProfiles(Profiles.of("prod")), appEnvironment);
    }

    static void check(boolean prodProfile, String appEnvironment) {
        if (prodProfile) {
            throw new IllegalStateException("o perfil sandbox não pode ser combinado com o perfil prod");
        }
        if (!"sandbox".equals(appEnvironment)) {
            throw new IllegalStateException("o perfil sandbox exige app.environment=sandbox (APP_ENV)");
        }
    }
}
