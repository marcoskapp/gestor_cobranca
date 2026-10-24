package br.com.gestorcobranca.telephony.application.port.out;

import br.com.gestorcobranca.telephony.domain.model.ProviderType;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Porta de saída para o provedor de telefonia/discador (CPaaS ou PABX/SIP). O provedor ainda não
 * foi escolhido (issue #12); o único adaptador é o simulador ({@code SANDBOX}, recusado no perfil
 * {@code prod}). Contrato neutro, modelado nos padrões comuns de CPaaS (chamada de saída com
 * {@code statusCallback}, "bridge"/"dial" para o ramal do operador, AMD, gravação por chamada):
 * <ul>
 *   <li>o adaptador traduz para a API do provedor com timeouts explícitos (3 s/10 s) e a métrica
 *       {@code integration.calls{provider,operation,outcome}};</li>
 *   <li>falha transitória (5xx, timeout, limite de taxa) → {@link TransientProviderException}
 *       (quem chamou tenta de novo); recusa definitiva → {@link DialResult#rejected};</li>
 *   <li>o andamento da chamada chega <b>somente por eventos</b> (webhook,
 *       {@link TelephonyWebhookAdapter}); as operações apenas pedem;</li>
 *   <li>{@code callId} (id interno) vai como referência do cliente (ex.: parâmetro do
 *       {@code statusCallback} ou cabeçalho SIP {@code X-Call-Ref}) para correlacionar eventos que
 *       cheguem antes do id do provedor ser gravado.</li>
 * </ul>
 * Números em E.164; nunca registrar números ou credenciais em log.
 */
public interface TelephonyProvider {

    ProviderType provider();

    /**
     * Clique para ligar: chama primeiro o ramal do operador e, atendido por ele, o devedor
     * (padrão "agent first"), conectando os dois.
     */
    DialResult originate(OriginateRequest request);

    /**
     * Discador (progressivo/preditivo): disca vários destinos de uma vez, devedor primeiro, com
     * detecção de secretária eletrônica. O atendimento chega por evento; a conexão com o operador é
     * pedida por {@link #bridge}. O resultado tem um item por pedido, na mesma ordem.
     */
    List<DialResult> dialBatch(ProviderContext context, List<DialRequest> requests);

    /** Conecta a chamada atendida ao ramal do operador escolhido. */
    void bridge(BridgeRequest request);

    /** Encerra a chamada (com a mensagem informativa de abandono quando {@code reason = ABANDONED}). */
    void hangup(HangupRequest request);

    /** Arquivo da gravação disponível no provedor. O chamador fecha o stream. */
    RecordingMedia fetchRecording(ProviderContext context, String recordingRef);

    /** Remove a gravação no provedor depois de copiada para o armazenamento próprio. */
    void deleteRecording(ProviderContext context, String recordingRef);

    /**
     * @param credentials credenciais da conta (JSON decodificado); nunca registrar em log
     * @param callerId    número de origem (bina) da assessoria/credor
     */
    record ProviderContext(UUID accountId, Map<String, String> credentials, String callerId) {
    }

    /**
     * @param agentEndpoint ramal/usuário SIP do operador
     * @param destination   telefone do devedor (E.164)
     */
    record OriginateRequest(ProviderContext context, UUID callId, String agentEndpoint, String destination,
                            int ringTimeoutSeconds, boolean record, boolean announceRecording) {
    }

    record DialRequest(UUID callId, String destination, int ringTimeoutSeconds, boolean detectMachine, boolean record,
                       boolean announceRecording) {
    }

    record BridgeRequest(ProviderContext context, UUID callId, String providerCallId, String agentEndpoint) {
    }

    record HangupRequest(ProviderContext context, UUID callId, String providerCallId, HangupReason reason) {
    }

    enum HangupReason {
        /** Operador ou supervisor encerrou. */
        OPERATOR,
        /** Sem operador livre: mensagem informativa e encerramento (abandono). */
        ABANDONED,
        /** Secretária eletrônica detectada (discador). */
        MACHINE,
        /** Cancelamento do sistema (campanha pausada/encerrada, conciliação). */
        CANCELLED
    }

    /**
     * @param providerCallId id do provedor (idempotência dos eventos)
     * @param errorCode      código estável da recusa (ex.: {@code SANDBOX_INVALID_NUMBER})
     */
    record DialResult(UUID callId, boolean accepted, String providerCallId, String errorCode, String errorDetail) {

        public static DialResult accepted(UUID callId, String providerCallId) {
            return new DialResult(callId, true, providerCallId, null, null);
        }

        public static DialResult rejected(UUID callId, String errorCode, String errorDetail) {
            return new DialResult(callId, false, null, errorCode, errorDetail);
        }
    }

    /** @param size bytes, ou -1 se desconhecido */
    record RecordingMedia(String contentType, long size, InputStream content) {
    }

    /** Falha transitória do provedor: a operação deve ser tentada novamente. */
    class TransientProviderException extends RuntimeException {

        public TransientProviderException(String message) {
            super(message);
        }

        public TransientProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
