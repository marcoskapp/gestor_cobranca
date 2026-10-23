package br.com.gestorcobranca.incentive.domain.service;

import br.com.gestorcobranca.incentive.domain.model.commitment.CallbackCommitment;
import br.com.gestorcobranca.incentive.domain.model.commitment.CallbackCommitmentStatus;
import br.com.gestorcobranca.incentive.domain.model.commitment.PromiseOutcome;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Regras puras que transformam os eventos de retorno e de promessa em situações de compromisso
 * (indicadores "retornos cumpridos" e "promessas cumpridas", §2.1 e §2.2). O resultado independe
 * da ordem de entrega dos eventos:
 * <ul>
 *   <li>retorno: a situação de cada compromisso só avança de grau
 *       ({@link CallbackCommitmentStatus#merge}); o compromisso aberto que não é o agendamento
 *       mais recente da tarefa foi substituído por reagendamento ({@code SUPERSEDED}); as dimensões
 *       (responsável, credor, carteira) vêm do evento mais recente;</li>
 *   <li>promessa: vale a situação do evento mais recente; o registro é contado uma única vez, pelo
 *       evento de registro ({@code previousStatus = null}).</li>
 * </ul>
 */
public final class CommitmentPolicy {

    private CommitmentPolicy() {
    }

    /** Dados de um {@code CallbackStatusChanged} relevantes para o compromisso. */
    public record CallbackSignal(UUID taskId, Instant dueAt, UUID caseId, UUID creditorId, UUID portfolioId,
                                 UUID assigneeId, CallbackCommitmentStatus status, Instant occurredAt) {
    }

    /** Mudança de um compromisso ({@code before} null = compromisso novo). */
    public record Change<T>(T before, T after) {
    }

    /**
     * @param taskCommitments compromissos já conhecidos da tarefa
     * @return compromissos da tarefa que mudaram
     */
    public static List<Change<CallbackCommitment>> applyCallback(Collection<CallbackCommitment> taskCommitments,
                                                                 CallbackSignal s) {
        Map<Instant, CallbackCommitment> current = new LinkedHashMap<>();
        taskCommitments.forEach(c -> current.put(c.dueAt(), c));
        Map<Instant, CallbackCommitment> next = new LinkedHashMap<>(current);
        CallbackCommitment existing = current.get(s.dueAt());
        boolean scheduling = s.status() == CallbackCommitmentStatus.SCHEDULED;
        CallbackCommitment updated;
        if (existing == null) {
            updated = new CallbackCommitment(s.taskId(), s.dueAt(), s.caseId(), s.creditorId(), s.portfolioId(),
                    s.assigneeId(), s.status(), scheduling ? s.occurredAt() : null, s.occurredAt());
        } else {
            boolean newer = !s.occurredAt().isBefore(existing.lastEventAt());
            Instant scheduledAt = existing.scheduledAt();
            if (scheduling) {
                scheduledAt = scheduledAt == null || s.occurredAt().isBefore(scheduledAt) ? s.occurredAt() : scheduledAt;
            }
            updated = new CallbackCommitment(s.taskId(), s.dueAt(),
                    newer && s.caseId() != null ? s.caseId() : existing.caseId(),
                    newer && s.creditorId() != null ? s.creditorId() : existing.creditorId(),
                    newer && s.portfolioId() != null ? s.portfolioId() : existing.portfolioId(),
                    newer ? s.assigneeId() : existing.assigneeId(),
                    existing.status().merge(s.status()), scheduledAt,
                    newer ? s.occurredAt() : existing.lastEventAt());
        }
        next.put(s.dueAt(), updated);
        Instant latestScheduling = next.values().stream().map(CallbackCommitment::scheduledAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        if (latestScheduling != null) {
            next.replaceAll((due, c) -> c.status().isOpen() && c.scheduledAt() != null
                    && c.scheduledAt().isBefore(latestScheduling)
                    ? c.withStatus(c.status().merge(CallbackCommitmentStatus.SUPERSEDED)) : c);
        }
        List<Change<CallbackCommitment>> changes = new ArrayList<>();
        next.forEach((due, after) -> {
            CallbackCommitment before = current.get(due);
            if (!after.equals(before)) {
                changes.add(new Change<>(before, after));
            }
        });
        return changes;
    }

    /** Dados de um {@code PromiseStatusChanged} relevantes para o desfecho. */
    public record PromiseSignal(UUID promiseId, UUID caseId, UUID creditorId, UUID portfolioId, UUID operatorId,
                                LocalDate dueDate, String previousStatus, String status, Instant occurredAt) {

        /** Evento de registro da promessa. */
        public boolean registration() {
            return previousStatus == null;
        }
    }

    /**
     * @param before        situação anterior (null = primeira notícia da promessa)
     * @param registeredNow o registro deve ser contado agora (primeira vez que o evento de registro chega)
     */
    public record PromiseChange(PromiseOutcome before, PromiseOutcome after, boolean registeredNow) {
    }

    public static PromiseChange applyPromise(PromiseOutcome existing, PromiseSignal s) {
        boolean registeredNow = s.registration() && (existing == null || existing.registeredAt() == null);
        Instant registeredAt = registeredNow ? s.occurredAt() : existing == null ? null : existing.registeredAt();
        PromiseOutcome after;
        if (existing == null || !s.occurredAt().isBefore(existing.statusAt())) {
            after = new PromiseOutcome(s.promiseId(), s.caseId(), s.creditorId(), s.portfolioId(), s.operatorId(),
                    s.dueDate(), s.status(), s.occurredAt(), registeredAt);
        } else {
            after = new PromiseOutcome(existing.promiseId(), existing.caseId(), existing.creditorId(),
                    existing.portfolioId(), existing.operatorId(), existing.dueDate(), existing.status(),
                    existing.statusAt(), registeredAt);
        }
        return new PromiseChange(existing, after, registeredNow);
    }
}
