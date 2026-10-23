package br.com.gestorcobranca.telephony.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Chamadas vinculadas ao caso (F20). Toda discagem passa por {@code ContactGate}: o clique para
 * ligar consulta o porteiro na hora (negação imediata → chamada {@code FAILED/CONTACT_DENIED}, sem
 * discar) e o job de discagem autoriza e registra a tentativa imediatamente antes de chamar o
 * provedor. Ao encerrar, a chamada do operador aguarda a tabulação (pós-atendimento); chamadas sem
 * operador são tabuladas automaticamente. A tabulação registra a interação no caso pelo port do
 * collection e o encerramento publica {@code CallCompleted}.
 * <p>
 * Escopo: credor da chamada no escopo do ator (404 fora dele). Quem não supervisiona
 * ({@code TELEPHONY_SUPERVISE}) lista apenas as próprias chamadas ou as de um caso; encerra e tabula
 * apenas as próprias.
 */
public interface CallUseCases {

    /**
     * Clique para ligar da estação de cobrança: exige o operador habilitado (ramal) e disponível;
     * reserva o operador e enfileira a discagem.
     *
     * @param idempotencyKey reenvio seguro do comando (opcional)
     */
    CallView clickToCall(ClickToCallCommand command, String idempotencyKey);

    CallView get(UUID callId);

    PageResponse<CallView> search(CallFilter filter, PageQuery page);

    /** Chamadas do operador corrente aguardando tabulação. */
    List<CallView> awaitingTabulation();

    /** Encerra a chamada em andamento (o próprio operador ou supervisão). */
    CallView hangup(UUID callId);

    /**
     * Tabulação pelo operador da chamada: registra a interação no caso (com promessa/retorno
     * quando a tabulação exige) e encerra o pós-atendimento. Chamada recebida não identificada
     * exige {@code caseId}.
     */
    CallView tabulate(UUID callId, TabulateCommand command);

    /**
     * Arquivo da gravação para ouvir/baixar. Exige {@code CALL_RECORDING_LISTEN}, a chamada no
     * escopo e a finalidade; registra {@code FILE_DOWNLOADED} no log de acesso. O chamador fecha o
     * stream.
     *
     * @param purpose QUALITY_MONITORING, DISPUTE, DEBTOR_REQUEST, LEGAL ou AUDIT
     */
    RecordingFile openRecording(UUID callId, String purpose);

    /** @param contactId telefone do devedor (nulo = o de maior prioridade) */
    record ClickToCallCommand(UUID caseId, UUID contactId) {
    }

    /**
     * @param tabulationCode código da tabulação do collection
     * @param caseId         caso da chamada recebida ainda não identificada
     */
    record TabulateCommand(String tabulationCode, String note, PromiseInput promise, CallbackInput callback,
                           UUID caseId) {
    }

    record PromiseInput(BigDecimal amount, LocalDate dueDate) {
    }

    /** @param dueAt horário combinado no fuso de operação */
    record CallbackInput(LocalDateTime dueAt, UUID assigneeUserId, String note) {
    }

    /**
     * @param operatorId {@code me} na API = o próprio operador
     * @param status     estado da chamada; {@code result} resultado consolidado
     */
    record CallFilter(UUID caseId, UUID debtorId, UUID creditorId, UUID operatorId, UUID campaignId, String status,
                      String result, String direction, Instant from, Instant to) {
    }

    /**
     * @param numberMasked         número mascarado (nunca o valor completo)
     * @param denialReasons        motivos do porteiro de contato quando negada
     * @param suggestedTabulation  tabulação sugerida pelo resultado (pós-atendimento)
     * @param interactionState     NONE, AWAITING_TABULATION, PENDING, RECORDED ou SKIPPED
     */
    record CallView(UUID id, String direction, String origin, String status, String result, UUID creditorId,
                    UUID portfolioId, UUID debtorId, UUID caseId, UUID contactId, UUID operatorId, UUID campaignId,
                    String numberMasked, int attemptNumber, Instant queuedAt, Instant dialStartedAt, Instant ringingAt,
                    Instant answeredAt, Instant connectedAt, Instant endedAt, Instant tabulatedAt, Integer ringSeconds,
                    Integer talkSeconds, Integer wrapUpSeconds, String amdResult, boolean abandoned, String hangupSide,
                    String failureCode, List<String> denialReasons, Instant nextAllowedAt, boolean recordingAnnounced,
                    String tabulationCode, String suggestedTabulation, String interactionState, UUID interactionId,
                    RecordingInfo recording, long version) {
    }

    /** @param status PENDING, STORED, FAILED ou PURGED */
    record RecordingInfo(UUID id, String status, Integer durationSeconds, boolean announced, Instant storedAt,
                         Instant purgedAt, String purgeReason) {
    }

    /** @param size bytes, ou -1 se desconhecido */
    record RecordingFile(String fileName, String contentType, long size, String sha256, InputStream content) {
    }
}
