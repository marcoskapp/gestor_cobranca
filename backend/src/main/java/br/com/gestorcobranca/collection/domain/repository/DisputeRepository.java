package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.shared.paging.ChangeQuery;

import br.com.gestorcobranca.collection.domain.model.Dispute;
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

    /** Leitura incremental: até {@code limit + 1} contestações do credor alteradas na janela, depois do cursor. */
    List<Dispute> findChanges(UUID creditorId, ChangeQuery query);

    Page<Dispute> search(DisputeSearchCriteria criteria, Pageable pageable);

    /** Contestações abertas por responsável (ausente = nenhuma). */
    java.util.Map<UUID, Long> countOpenByAssignees(java.util.Collection<UUID> assigneeIds);

    /** Contestações do devedor com ajuste sugerido pendente. */
    List<Dispute> findPendingAdjustmentsByDebtorId(UUID debtorId);

    /** Ajustes pendentes (varredura diária), em ordem de resolução. */
    List<Dispute> findPendingAdjustments(int limit);
}
