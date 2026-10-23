package br.com.gestorcobranca.communication.application.port.out;

import br.com.gestorcobranca.communication.domain.model.ProviderType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Validação e leitura das notificações de um provedor. A assinatura é validada sobre o corpo
 * bruto, com o segredo da conta, antes de qualquer interpretação do conteúdo.
 */
public interface ProviderWebhookAdapter {

    ProviderType provider();

    /** Assinatura do corpo (ex.: {@code X-Hub-Signature-256: sha256=<hex>}), comparação em tempo constante. */
    boolean validSignature(Map<String, String> credentials, String signatureHeader, byte[] body);

    /** Handshake de verificação da assinatura do webhook (ex.: {@code hub.verify_token}), tempo constante. */
    boolean validVerifyToken(Map<String, String> credentials, String token);

    /** @throws IllegalArgumentException corpo malformado */
    InboundBatch parse(byte[] body);

    record InboundBatch(List<InboundMessage> messages, List<StatusUpdate> statuses) {
    }

    /**
     * @param from  telefone/e-mail do remetente como informado pelo provedor
     * @param type  tipo do conteúdo (text, image, audio, button, interactive…)
     * @param text  texto (ou legenda/descrição) da mensagem; nulo para mídia sem texto
     */
    record InboundMessage(String providerMessageId, String from, String type, String text, Instant timestamp) {
    }

    /** @param status DELIVERED, READ, FAILED ou SENT (ignorado: o aceite já marca SENT) */
    record StatusUpdate(String providerMessageId, String status, Instant timestamp, String errorCode,
                        String errorDetail) {
    }
}
