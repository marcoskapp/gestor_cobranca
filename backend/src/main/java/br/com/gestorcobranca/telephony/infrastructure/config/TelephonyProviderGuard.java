package br.com.gestorcobranca.telephony.infrastructure.config;

import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Proteções de configuração dos provedores reais: {@code app.telephony.alvaria.allow-insecure-http}
 * (HTTP sem TLS, só para testes com servidor local) impede a subida no perfil {@code prod}; a
 * homologação de provedores com contrato a confirmar ({@code app.telephony.homologation.providers})
 * é registrada em aviso na subida — ela é ligada só no ambiente de homologação da AWS (também
 * perfil {@code prod}), nunca no de produção (variável de ambiente do Terraform do ambiente).
 */
@Component
class TelephonyProviderGuard {

    private static final Logger LOG = LoggerFactory.getLogger(TelephonyProviderGuard.class);

    TelephonyProviderGuard(TelephonyProperties properties, Environment environment) {
        if (properties.alvaria().allowInsecureHttp() && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("app.telephony.alvaria.allow-insecure-http não é permitido no perfil prod");
        }
        if (!properties.homologation().providers().isEmpty()) {
            LOG.atWarn().addKeyValue("providers", String.join(",", properties.homologation().providers()))
                    .log("Telefonia: provedores com contrato a confirmar liberados para discar (homologação)");
        }
    }
}
