package br.com.gestorcobranca.communication.application.port.in;

import java.util.Optional;

/**
 * <strong>Uso interno do adaptador web de webhooks.</strong> Resolve a conta pelo
 * {@code routingKey}, valida a assinatura antes de qualquer processamento (inválida →
 * {@code WEBHOOK_REJECTED} no log de acesso), descarta reentregas do mesmo corpo e eventos mais
 * antigos que a janela de replay, e processa mensagens e status no tenant da conta.
 */
public interface WebhookUseCases {

    /** Handshake de verificação (WhatsApp: {@code hub.mode=subscribe}); vazio = recusado. */
    Optional<String> verifySubscription(String provider, String routingKey, String mode, String verifyToken,
                                        String challenge, String clientIp);

    Outcome receive(String provider, String routingKey, String signatureHeader, byte[] body, String clientIp);

    enum Outcome {
        /** Processado (mensagens/status novos aplicados). */
        ACCEPTED,
        /** Corpo idêntico já processado: nenhum efeito. */
        DUPLICATE,
        /** Rota inexistente ou inativa. */
        UNKNOWN_ROUTE,
        /** Assinatura ausente ou inválida. */
        INVALID_SIGNATURE,
        /** Corpo assinado mas ilegível. */
        MALFORMED
    }
}
