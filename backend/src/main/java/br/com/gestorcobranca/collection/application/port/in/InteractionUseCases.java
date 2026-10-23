package br.com.gestorcobranca.collection.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Interações com o devedor (append-only) e tabulação. Um registro, numa única transação:
 * <ol>
 *   <li>valida a tabulação ativa e as exigências das flags (observação, promessa, retorno);</li>
 *   <li>grava a interação (observação criptografada) e atualiza os indicadores do caso;</li>
 *   <li>contato efetivo leva o caso a {@code IN_NEGOTIATION}; promessa registra a promessa
 *       (substituindo a pendente) e leva a {@code PROMISE}; retorno agenda a tarefa;</li>
 *   <li>conclui retornos em aberto já vencidos do caso (ou o indicado em {@code completesTaskId});</li>
 *   <li>operador que registra num caso sem dono passa a ser o responsável.</li>
 * </ol>
 * Publica {@code InteractionRecorded} e, quando houver, os eventos de promessa e retorno.
 * Integrações (telefonia, comunicação, assistente) informam {@code source} e {@code externalRef}:
 * reenvios devolvem a interação já registrada, sem duplicar.
 */
public interface InteractionUseCases {

    RecordResult record(UUID caseId, RecordInteractionCommand command);

    PageResponse<InteractionView> list(UUID caseId, PageQuery page);

    /**
     * @param channel        PHONE, WHATSAPP, EMAIL, SMS, IN_PERSON, PORTAL ou LETTER
     * @param direction      OUTBOUND ou INBOUND
     * @param tabulationCode código da tabulação (alternativa a {@code tabulationId})
     * @param startedAt      início (nulo = agora; até 7 dias no passado, nunca no futuro)
     * @param endedAt        fim (opcional; define a duração)
     * @param promise        obrigatória quando a tabulação exige promessa
     * @param callback       obrigatório quando a tabulação exige retorno
     * @param completesTaskId tarefa em aberto do caso concluída por esta interação (opcional)
     * @param source         MANUAL (padrão), TELEPHONY, COMMUNICATION, ASSISTANT ou SYSTEM
     * @param externalRef    id externo (chamada, mensagem) para idempotência de integrações
     */
    record RecordInteractionCommand(String channel, String direction, UUID tabulationId, String tabulationCode,
                                    String note, UUID contactId, Instant startedAt, Instant endedAt,
                                    PromiseInput promise, CallbackInput callback, UUID completesTaskId, String source,
                                    String externalRef) {
    }

    /** @param dueDate data prometida (fuso de operação) */
    record PromiseInput(BigDecimal amount, LocalDate dueDate) {
    }

    /**
     * @param dueAt horário combinado, no fuso de operação ({@code America/Sao_Paulo})
     * @param assigneeUserId responsável pelo retorno (nulo = quem registrou; sem operador = fila do caso)
     */
    record CallbackInput(LocalDateTime dueAt, UUID assigneeUserId, String note) {
    }

    /**
     * @param duplicate  a interação já existia (reenvio de integração); nada mais foi feito
     * @param promise    promessa registrada por esta interação, se houver
     * @param callback   retorno agendado por esta interação, se houver
     */
    record RecordResult(InteractionView interaction, boolean duplicate, PromiseUseCases.PromiseView promise,
                        TaskUseCases.TaskView callback, CaseUseCases.CaseView collectionCase) {
    }

    /** @param actorType USER, SYSTEM, ASSISTANT ou INTEGRATION */
    record InteractionView(UUID id, UUID caseId, UUID debtorId, UUID creditorId, UUID portfolioId, String channel,
                           String direction, UUID tabulationId, String outcomeCode, boolean effectiveContact,
                           String note, UUID contactId, Instant startedAt, Instant endedAt, Integer durationSeconds,
                           UUID operatorId, String actorType, String source, String externalRef, Instant createdAt) {
    }
}
