package br.com.gestorcobranca.collection.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Tarefas e retornos do caso. Datas e horas são informadas e exibidas no fuso de operação
 * ({@code TimeConfig.BUSINESS_ZONE}) e gravadas como instante UTC. Um retorno concluído até o
 * horário combinado mais a tolerância da assessoria é <b>cumprido</b>; passada a tolerância, o
 * job de vencimento o marca <b>atrasado</b> ({@code MISSED}) e recoloca o caso como próxima ação.
 * Publica {@code CallbackStatusChanged} (agendado, cumprido, atrasado, cumprido com atraso,
 * cancelado).
 */
public interface TaskUseCases {

    TaskView schedule(UUID caseId, ScheduleTaskCommand command);

    /** @param interactionId interação que cumpriu a tarefa (opcional) */
    TaskView complete(UUID taskId, UUID interactionId);

    TaskView cancel(UUID taskId, String reason);

    TaskView reschedule(UUID taskId, LocalDateTime dueAt);

    TaskView reassign(UUID taskId, UUID userId);

    List<TaskView> listByCase(UUID caseId);

    /**
     * Agenda do operador entre as datas (inclusive, fuso de operação).
     *
     * @param userId nulo = o próprio usuário; de outro usuário exige {@code CASE_ASSIGN}
     * @param statuses PENDING, MISSED, DONE, CANCELLED (vazio = em aberto)
     */
    PageResponse<TaskView> agenda(UUID userId, LocalDate from, LocalDate to, List<String> statuses, PageQuery page);

    /**
     * @param type           CALLBACK ou FOLLOW_UP
     * @param dueAt          data e hora no fuso de operação
     * @param assigneeUserId responsável (nulo = operador do caso, ou a fila)
     */
    record ScheduleTaskCommand(String type, LocalDateTime dueAt, UUID assigneeUserId, String note) {
    }

    /**
     * @param dueAtLocal       horário combinado no fuso de operação
     * @param toleranceUntil   limite para contar como cumprido
     * @param completedOnTime  nulo enquanto não concluída
     */
    record TaskView(UUID id, UUID caseId, UUID creditorId, String type, String status, Instant dueAt,
                    LocalDateTime dueAtLocal, Instant toleranceUntil, UUID assigneeUserId, String note,
                    UUID originInteractionId, Instant completedAt, UUID completedBy, UUID completionInteractionId,
                    Boolean completedOnTime, Instant missedAt, String cancelReason, long version) {
    }
}
