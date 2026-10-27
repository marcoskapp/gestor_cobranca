package br.com.gestorcobranca.telephony.application.port.in;

import java.util.Map;

/**
 * <strong>Uso interno do adaptador web de webhooks.</strong> Resolve a conta pelo
 * {@code routingKey}, valida a assinatura sobre a requisição bruta antes de qualquer processamento
 * (inválida → {@code WEBHOOK_REJECTED} no log de acesso), descarta reentregas do mesmo corpo,
 * eventos repetidos (id do evento) e fora da janela de replay, aplica os eventos de chamada no
 * tenant da conta e, para provedores que pedem instruções por webhook (TwiML/Plivo XML), devolve a
 * resposta com as instruções da chamada.
 */
public interface TelephonyWebhookUseCases {

    Reply handle(WebhookHttpRequest request);

    /** Provedores que assinam só o corpo (sem URL nem consulta). */
    default Outcome receive(String routingKey, Map<String, String> headers, byte[] body, String clientIp) {
        return handle(new WebhookHttpRequest(routingKey, null, Map.of(), headers, body, null, clientIp)).outcome();
    }

    /**
     * @param url         URL pública exatamente como chamada pelo provedor (assinaturas que cobrem a URL)
     * @param query       consulta decodificada (primeiro valor por nome)
     * @param headers     cabeçalhos com nomes em minúsculas
     * @param contentType tipo do corpo
     */
    record WebhookHttpRequest(String routingKey, String url, Map<String, String> query, Map<String, String> headers,
                              byte[] body, String contentType, String clientIp) {
    }

    /** @param contentType tipo do corpo da resposta (nulo = sem corpo) */
    record Reply(Outcome outcome, String contentType, String body) {

        public static Reply of(Outcome outcome) {
            return new Reply(outcome, null, null);
        }
    }

    enum Outcome {
        ACCEPTED,
        DUPLICATE,
        UNKNOWN_ROUTE,
        INVALID_SIGNATURE,
        MALFORMED
    }
}
