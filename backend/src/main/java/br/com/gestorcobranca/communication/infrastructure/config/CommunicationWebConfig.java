package br.com.gestorcobranca.communication.infrastructure.config;

import br.com.gestorcobranca.shared.security.PublicEndpoint;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Endpoints públicos do módulo: webhooks do WhatsApp (assinatura validada antes de qualquer processamento). */
@Configuration
class CommunicationWebConfig {

    @Bean
    PublicEndpoint whatsappWebhook() {
        return new PublicEndpoint(null, ApiPaths.WEBHOOKS + "/whatsapp/*",
                "Webhook do WhatsApp Cloud API (X-Hub-Signature-256, handshake por verify_token, limite de taxa e tamanho)");
    }
}
