package br.com.gestorcobranca.telephony.infrastructure.config;

import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Proteções de configuração dos provedores reais: {@code allow-insecure-http} da Alvaria, Twilio
 * e Plivo (HTTP sem TLS, só para testes com servidor local) impede a subida no perfil {@code prod};
 * endereço público dos webhooks sem HTTPS gera aviso; a
 * homologação de provedores com contrato a confirmar ({@code app.telephony.homologation.providers})
 * é registrada em aviso na subida — ela é ligada só no ambiente de homologação da AWS (também
 * perfil {@code prod}), nunca no de produção (variável de ambiente do Terraform do ambiente).
 */
@Component
class TelephonyProviderGuard {

    private static final Logger LOG = LoggerFactory.getLogger(TelephonyProviderGuard.class);

    TelephonyProviderGuard(TelephonyProperties properties, Environment environment) {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        if (properties.alvaria().allowInsecureHttp() && prod) {
            throw new IllegalStateException("app.telephony.alvaria.allow-insecure-http não é permitido no perfil prod");
        }
        if ((properties.twilio().allowInsecureHttp() || properties.plivo().allowInsecureHttp()) && prod) {
            throw new IllegalStateException("app.telephony.twilio|plivo.allow-insecure-http não é permitido no perfil prod");
        }
        boolean cpaas = properties.twilio().enabled() || properties.plivo().enabled();
        if (cpaas && prod && !properties.webhook().publicBaseUrl().startsWith("https://")) {
            LOG.atWarn().log("Telefonia: app.telephony.webhook.public-base-url sem HTTPS; Twilio e Plivo exigem o "
                    + "endereço público HTTPS da aplicação para entregar os webhooks");
        }
        if (!properties.homologation().providers().isEmpty()) {
            LOG.atWarn().addKeyValue("providers", String.join(",", properties.homologation().providers()))
                    .log("Telefonia: provedores com contrato a confirmar liberados para discar (homologação)");
        }
    }
}
