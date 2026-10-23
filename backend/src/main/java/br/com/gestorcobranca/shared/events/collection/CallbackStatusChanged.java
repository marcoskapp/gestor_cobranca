package br.com.gestorcobranca.shared.events.collection;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Retorno (callback) agendado com o devedor mudou de situação. Fonte do indicador "retornos
 * cumpridos" (callbacks realizados até o horário ÷ retornos vencidos) do módulo incentive.
 * <p>
 * Situações ({@code status}):
 * <ul>
 *   <li>{@link #SCHEDULED} — agendado (ou reagendado: novo compromisso);</li>
 *   <li>{@link #KEPT} — cumprido até o horário combinado mais a tolerância;</li>
 *   <li>{@link #MISSED} — atrasado: a tolerância passou sem cumprimento (publicado uma vez por compromisso);</li>
 *   <li>{@link #COMPLETED_LATE} — cumprido depois de atrasado (não conta como cumprido);</li>
 *   <li>{@link #CANCELLED} — cancelado antes de vencer (sai do denominador).</li>
 * </ul>
 * Denominador sugerido: {@code KEPT + MISSED} por dia de {@code dueAt} no fuso de operação.
 *
 * @param assigneeId     responsável pelo retorno (null = fila do caso)
 * @param dueAt          horário combinado
 * @param toleranceUntil fim da tolerância
 * @param completedBy    quem cumpriu (KEPT/COMPLETED_LATE)
 */
public record CallbackStatusChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID taskId,
        UUID caseId,
        UUID debtorId,
        UUID creditorId,
        UUID portfolioId,
        UUID assigneeId,
        UUID teamId,
        Instant dueAt,
        Instant toleranceUntil,
        String status,
        UUID completedBy) implements IntegrationEvent {

    public static final String SCHEDULED = "SCHEDULED";
    public static final String KEPT = "KEPT";
    public static final String MISSED = "MISSED";
    public static final String COMPLETED_LATE = "COMPLETED_LATE";
    public static final String CANCELLED = "CANCELLED";
}
