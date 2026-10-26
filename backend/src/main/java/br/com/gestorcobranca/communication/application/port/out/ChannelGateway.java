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

    /**
     * @param parameters     valores na ordem das variáveis do template
     * @param authentication template de autenticação (código de acesso): o único parâmetro é o código,
     *                       enviado no corpo e no botão "copiar código" (formato da Meta)
     */
    record ProviderTemplate(String name, String language, List<String> parameters, boolean authentication) {

        public ProviderTemplate {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
            if (authentication && parameters.size() != 1) {
                throw new IllegalArgumentException("Template de autenticação tem exatamente um parâmetro (o código)");
            }
        }

        /** Template comum: só parâmetros do corpo. */
        public static ProviderTemplate standard(String name, String language, List<String> parameters) {
            return new ProviderTemplate(name, language, parameters, false);
        }

        /** Template de autenticação: o código vai no corpo e no botão "copiar código". */
        public static ProviderTemplate authentication(String name, String language, String code) {
            return new ProviderTemplate(name, language, List.of(code), true);
        }
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
