package br.com.gestorcobranca.telephony.application.port.in;

import java.util.Map;

/**
 * <strong>Uso interno do adaptador web de webhooks.</strong> Resolve a conta pelo
 * {@code routingKey}, valida a assinatura sobre o corpo bruto antes de qualquer processamento
 * (inválida → {@code WEBHOOK_REJECTED} no log de acesso), descarta reentregas do mesmo corpo,
 * eventos repetidos (id do evento) e fora da janela de replay, e aplica os eventos de chamada no
 * tenant da conta.
 */
public interface TelephonyWebhookUseCases {

    /** @param headers cabeçalhos com nomes em minúsculas */
    Outcome receive(String routingKey, Map<String, String> headers, byte[] body, String clientIp);

    enum Outcome {
        ACCEPTED,
        DUPLICATE,
        UNKNOWN_ROUTE,
        INVALID_SIGNATURE,
        MALFORMED
    }
}
