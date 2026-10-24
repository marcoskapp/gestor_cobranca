package br.com.gestorcobranca.telephony.infrastructure.config;

import br.com.gestorcobranca.shared.security.PublicEndpoint;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** Endpoint público do módulo: webhook do provedor de telefonia (assinatura validada antes de qualquer processamento). */
@Configuration
class TelephonyWebConfig {

    @Bean
    PublicEndpoint telephonyWebhook() {
        return new PublicEndpoint(HttpMethod.POST, ApiPaths.WEBHOOKS + "/telephony/*",
                "Webhook do provedor de telefonia (assinatura HMAC com instante, recibo do corpo, idempotência por evento, "
                        + "limite de taxa e tamanho)");
    }
}
