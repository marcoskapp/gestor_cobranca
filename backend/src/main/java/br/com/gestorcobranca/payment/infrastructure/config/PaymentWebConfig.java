package br.com.gestorcobranca.payment.infrastructure.config;

import br.com.gestorcobranca.shared.security.PublicEndpoint;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** Endpoint público do módulo: webhook Pix (autenticação mTLS/HMAC validada antes de qualquer processamento). */
@Configuration
class PaymentWebConfig {

    @Bean
    PublicEndpoint pixWebhook() {
        return new PublicEndpoint(HttpMethod.POST, ApiPaths.WEBHOOKS + "/pix/**",
                "Webhook Pix (API Pix do BACEN): mTLS com certificado do PSP fixado na conta ou HMAC, "
                        + "recibo do corpo, janela de replay, limite de taxa e tamanho; confirmação por consulta ao PSP");
    }
}
