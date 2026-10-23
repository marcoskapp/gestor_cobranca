package br.com.gestorcobranca.incentive.domain.model.commitment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Compromisso de retorno (tarefa × horário combinado) materializado dos eventos
 * {@code CallbackStatusChanged}. O reagendamento cria outro compromisso da mesma tarefa.
 *
 * @param assigneeId   responsável pelo retorno segundo o evento mais recente (null = fila do caso)
 * @param scheduledAt  instante do evento de agendamento deste horário (null se ainda não chegou)
 * @param lastEventAt  instante do evento mais recente aplicado (dimensões vêm dele)
 */
public record CallbackCommitment(UUID taskId, Instant dueAt, UUID caseId, UUID creditorId, UUID portfolioId,
                                 UUID assigneeId, CallbackCommitmentStatus status, Instant scheduledAt,
                                 Instant lastEventAt) {

    public CallbackCommitment {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(dueAt, "dueAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(lastEventAt, "lastEventAt");
    }

    public CallbackCommitment withStatus(CallbackCommitmentStatus newStatus) {
        return new CallbackCommitment(taskId, dueAt, caseId, creditorId, portfolioId, assigneeId, newStatus, scheduledAt,
                lastEventAt);
    }

    /** Contribuição para os fatos (zero quando não há responsável humano). */
    public CommitmentTally tally() {
        return assigneeId == null ? CommitmentTally.ZERO : status.tally();
    }
}
