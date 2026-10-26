package br.com.gestorcobranca.telephony.infrastructure.provider.alvaria;

import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.DialRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupReason;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Contrato da API da Alvaria CX — A CONFIRMAR com a Alvaria (anexo A de
 * {@code docs/tecnico/desenho/telefonia.md}).</b>
 * <p>
 * A Alvaria não publica a documentação da API de CTI/outbound (acesso por contrato e pela comunidade
 * de parceiros). Tudo o que depende do formato do fornecedor está <b>isolado nesta classe</b>:
 * caminhos, nomes de campos, cabeçalhos e códigos. O adaptador ({@link AlvariaTelephonyProvider})
 * implementa só a mecânica confirmável sem o fornecedor — OAuth 2.0 <i>client credentials</i> com
 * cache do token, HTTPS no domínio permitido, timeouts, classificação de erros (429/5xx/rede →
 * transitório; 4xx → recusa), referência do cliente (id interno) e métricas — e por isso o
 * adaptador declara {@code CONTRACT_PENDING}: só disca com a homologação ligada para
 * {@code ALVARIA}.
 * <p>
 * Ao receber a documentação/ambiente de homologação: ajustar as constantes e os corpos abaixo,
 * ajustar {@code AlvariaTelephonyProviderTest} ao formato real, rodar o roteiro de homologação do
 * anexo A e só então mudar {@code readiness()} para {@code READY}.
 */
final class AlvariaApiContract {

    /** A CONFIRMAR: emissão de token OAuth 2.0 (client credentials). */
    static final String TOKEN_PATH = "/oauth2/token";
    /** A CONFIRMAR: chamada "operador primeiro" (clique para ligar). */
    static final String ORIGINATE_PATH = "/api/v1/calls/agent-first";
    /** A CONFIRMAR: discagem em lote com AMD (devedor primeiro). */
    static final String BATCH_PATH = "/api/v1/calls/batch";
    /** A CONFIRMAR: conexão da chamada atendida ao ramal do operador. */
    static final String BRIDGE_PATH = "/api/v1/calls/{providerCallId}/bridge";
    /** A CONFIRMAR: encerramento (com mensagem de abandono). */
    static final String HANGUP_PATH = "/api/v1/calls/{providerCallId}/hangup";
    /** A CONFIRMAR: arquivo da gravação (GET) e remoção (DELETE). */
    static final String RECORDING_PATH = "/api/v1/recordings/{recordingRef}";
    /** A CONFIRMAR: deduplicação do pedido pelo fornecedor (repetição segura após tempo esgotado). */
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    /** A CONFIRMAR: tamanho máximo do lote aceito. */
    static final int BATCH_MAX = 50;

    private AlvariaApiContract() {
    }

    static Map<String, Object> originate(UUID callId, String agentEndpoint, String destination, String callerId,
                                         int ringTimeoutSeconds, boolean record, boolean announceRecording) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientReference", callId.toString());
        body.put("agentEndpoint", agentEndpoint);
        body.put("destination", destination);
        body.put("callerId", callerId);
        body.put("ringTimeoutSeconds", ringTimeoutSeconds);
        body.put("record", record);
        body.put("announceRecording", announceRecording);
        return body;
    }

    static Map<String, Object> batch(List<DialRequest> requests, String callerId) {
        return Map.of("calls", requests.stream().map(r -> {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("clientReference", r.callId().toString());
            call.put("destination", r.destination());
            call.put("callerId", callerId);
            call.put("ringTimeoutSeconds", r.ringTimeoutSeconds());
            call.put("answeringMachineDetection", r.detectMachine());
            call.put("record", r.record());
            call.put("announceRecording", r.announceRecording());
            return call;
        }).toList());
    }

    static Map<String, Object> bridge(UUID callId, String agentEndpoint) {
        return Map.of("clientReference", callId.toString(), "agentEndpoint", agentEndpoint);
    }

    static Map<String, Object> hangup(UUID callId, HangupReason reason) {
        return Map.of("clientReference", callId.toString(), "reason", switch (reason) {
            case OPERATOR -> "AGENT";
            // a mensagem informativa de abandono é tocada pela plataforma (A CONFIRMAR: nome do recurso de áudio)
            case ABANDONED -> "ABANDONED_WITH_MESSAGE";
            case MACHINE -> "ANSWERING_MACHINE";
            case CANCELLED -> "CANCELLED";
        });
    }

    // --- respostas (A CONFIRMAR) ---

    /** Id da chamada na resposta de {@link #ORIGINATE_PATH}. */
    static final String RESPONSE_CALL_ID = "callId";
    /** Lista de resultados na resposta de {@link #BATCH_PATH}. */
    static final String RESPONSE_RESULTS = "results";
    static final String RESULT_REFERENCE = "clientReference";
    static final String RESULT_ACCEPTED = "accepted";
    static final String RESULT_CALL_ID = "callId";
    static final String RESULT_ERROR_CODE = "errorCode";
    /** Código de erro no corpo das respostas 4xx. */
    static final String ERROR_CODE = "errorCode";
}
