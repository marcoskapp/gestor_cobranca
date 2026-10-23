package br.com.gestorcobranca.payment.infrastructure.config;

import br.com.gestorcobranca.payment.application.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Impede que o PSP simulado seja habilitado em produção: com {@code app.payment.sandbox.enabled=true}
 * e perfil {@code prod}, a aplicação não sobe.
 */
@Component
class PaymentSandboxGuard {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentSandboxGuard.class);

    PaymentSandboxGuard(PaymentProperties properties, Environment environment) {
        if (properties.sandbox().enabled()) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("app.payment.sandbox.enabled não é permitido no perfil prod");
            }
            LOG.warn("PSP Pix simulado (sandbox) habilitado — somente para ambiente local/teste");
        }
    }
}
