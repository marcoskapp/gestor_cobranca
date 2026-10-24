package br.com.gestorcobranca.telephony.infrastructure.config;

import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Impede que o provedor de telefonia simulado seja habilitado em produção: com
 * {@code app.telephony.sandbox.enabled=true} e perfil {@code prod}, a aplicação não sobe.
 */
@Component
class TelephonySandboxGuard {

    private static final Logger LOG = LoggerFactory.getLogger(TelephonySandboxGuard.class);

    TelephonySandboxGuard(TelephonyProperties properties, Environment environment) {
        if (properties.sandbox().enabled()) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("app.telephony.sandbox.enabled não é permitido no perfil prod");
            }
            LOG.warn("Provedor de telefonia simulado (sandbox) habilitado — somente para ambiente local/teste");
        }
    }
}
