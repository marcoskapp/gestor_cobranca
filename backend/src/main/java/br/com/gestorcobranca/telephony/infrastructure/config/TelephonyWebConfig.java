package br.com.gestorcobranca.telephony.infrastructure.config;

import br.com.gestorcobranca.shared.security.BrowserPolicyContribution;
import br.com.gestorcobranca.shared.security.PublicEndpoint;
import br.com.gestorcobranca.shared.web.ApiPaths;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Locale;

/**
 * Endpoint público do módulo (webhook do provedor de telefonia, assinatura validada antes de
 * qualquer processamento) e o acréscimo à política do navegador do softphone WebRTC da Twilio
 * (decisão 584): {@code connect-src} só para a sinalização da borda configurada, o gateway de
 * eventos e os sons do SDK; {@code media-src} para os sons; microfone liberado só para a própria
 * origem. O SDK é empacotado no build ({@code script-src 'self'} inalterado).
 */
@Configuration
class TelephonyWebConfig {

    @Bean
    PublicEndpoint telephonyWebhook() {
        return new PublicEndpoint(HttpMethod.POST, ApiPaths.WEBHOOKS + "/telephony/*",
                "Webhook do provedor de telefonia (assinatura do provedor sobre o corpo e a URL, recibo do corpo, "
                        + "idempotência por evento, limite de taxa e tamanho)");
    }

    /** Origens da rede do Twilio Voice JavaScript SDK ("Voice SDKs Network Connectivity Requirements"). */
    @Bean
    @ConditionalOnExpression("${app.telephony.twilio.enabled:true}")
    BrowserPolicyContribution twilioSoftphonePolicy(TelephonyProperties properties) {
        String edge = properties.twilio().softphoneEdge().toLowerCase(Locale.ROOT);
        if (!edge.matches("[a-z\\-]{3,30}")) {
            throw new IllegalStateException("app.telephony.twilio.softphone-edge inválido");
        }
        return new BrowserPolicyContribution(
                List.of("wss://voice-js." + edge + ".twilio.com", "https://eventgw.twilio.com", "https://sdk.twilio.com"),
                List.of("https://sdk.twilio.com"),
                List.of("microphone"),
                "Softphone WebRTC da Twilio (SDK empacotado no build): sinalização, eventos de qualidade e sons");
    }
}
