package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.CollectionTask;
import br.com.gestorcobranca.collection.domain.model.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CollectionTaskRepository {

    CollectionTask save(CollectionTask task);

    Optional<CollectionTask> findById(UUID id);

    List<CollectionTask> findByCaseIdOrderByDueAtAsc(UUID caseId);

    List<CollectionTask> findByCaseIdAndStatusIn(UUID caseId, Collection<TaskStatus> statuses);

    /**
     * Agenda do operador no intervalo {@code [from, to)}.
     *
     * @param creditorIds credores do escopo do ator; {@code null} = sem restrição
     */
    Page<CollectionTask> findAgenda(UUID userId, Collection<UUID> creditorIds, Instant from, Instant to,
                                    Collection<TaskStatus> statuses, Pageable pageable);

    /** Retornos pendentes do operador já vencidos, do mais antigo ao mais recente. */
    List<CollectionTask> findDueForUser(UUID userId, Instant now, int limit);

    /** Tarefas pendentes cuja tolerância terminou antes do instante (job de vencimento). */
    List<CollectionTask> findPendingLateBefore(Instant before, int limit);

    /** Retornos em aberto do caso já vencidos (horário combinado alcançado) no instante. */
    List<CollectionTask> findOpenCallbacksDueBy(UUID caseId, Instant at);
}
