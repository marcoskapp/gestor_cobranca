package br.com.gestorcobranca.communication.application.port.out;

import br.com.gestorcobranca.communication.domain.model.ProviderType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Porta de envio por provedor (WhatsApp Cloud API, SMTP, sandbox; SMS aguarda provedor — issue
 * #12). Contrato neutro: o adaptador traduz para a API do provedor, com timeouts explícitos e a
 * métrica {@code integration.calls{provider,operation,outcome}}.
 * <p>
 * Falha transitória (5xx, timeout, limite de taxa) → {@link TransientSendException} (o job tenta
 * de novo); recusa definitiva → {@link SendResult#rejected}.
 */
public interface ChannelGateway {

    ProviderType provider();

    SendResult send(OutboundRequest request);

    /**
     * @param credentials  credenciais da conta (JSON já decodificado); nunca registrar em log
     * @param recipient    telefone E.164 (sem +) ou e-mail normalizado
     * @param template     template aprovado no provedor (WhatsApp fora da janela); nulo = texto livre
     * @param messageId    id interno (cabeçalhos de correlação, nunca dado pessoal)
     */
    record OutboundRequest(UUID accountId, Map<String, String> credentials, String senderAddress, String senderName,
                           String replyTo, String recipient, String subject, String text, ProviderTemplate template,
                           UUID messageId) {
    }

    /** @param parameters valores na ordem das variáveis do template */
    record ProviderTemplate(String name, String language, List<String> parameters) {
    }

    /**
     * @param accepted          o provedor aceitou a mensagem
     * @param providerMessageId id atribuído pelo provedor (idempotência dos webhooks)
     * @param errorCode         código estável da recusa (ex.: {@code WHATSAPP_131026})
     */
    record SendResult(boolean accepted, String providerMessageId, String errorCode, String errorDetail) {

        public static SendResult accepted(String providerMessageId) {
            return new SendResult(true, providerMessageId, null, null);
        }

        public static SendResult rejected(String errorCode, String errorDetail) {
            return new SendResult(false, null, errorCode, errorDetail);
        }
    }

    /** Falha transitória do provedor: o envio deve ser tentado novamente. */
    class TransientSendException extends RuntimeException {

        public TransientSendException(String message, Throwable cause) {
            super(message, cause);
        }

        public TransientSendException(String message) {
            super(message);
        }
    }
}
