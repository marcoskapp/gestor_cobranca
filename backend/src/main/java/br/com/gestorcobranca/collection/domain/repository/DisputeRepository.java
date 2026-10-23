package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface DisputeRepository {

    Dispute save(Dispute dispute);

    Optional<Dispute> findById(UUID id);

    List<Dispute> findByCaseIdOrderByOpenedAtDesc(UUID caseId);

    /** Há contestação aberta e bloqueante no caso. */
    boolean existsBlockingByCaseId(UUID caseId);

    /** Abertas, ainda não marcadas como vencidas, com prazo antes de {@code today}. */
    List<Dispute> findOverdueCandidates(LocalDate today, int limit);

    /**
     * @param allowedCreditorIds nulo = sem restrição
     * @param statuses           nulo = todos
     */
    Page<Dispute> search(Set<UUID> allowedCreditorIds, UUID creditorId, Set<DisputeStatus> statuses, Boolean overdue,
                         Pageable pageable);
}
