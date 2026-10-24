package br.com.gestorcobranca.scoring.domain.repository;

import br.com.gestorcobranca.scoring.domain.model.ScoringRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScoringRunRepository {

    ScoringRun save(ScoringRun run);

    Optional<ScoringRun> findById(UUID id);

    /** Escoragem em lote da data de referência (no máximo uma). */
    Optional<ScoringRun> findBatch(LocalDate referenceDate);

    /** Escoragem em lote mais recente (taxas por carteira usadas nos recálculos do dia). */
    Optional<ScoringRun> findLatestBatch();

    /** Escoragens com snapshot concluídas cujo recuperado × esperado ainda não foi medido. */
    List<ScoringRun> findSnapshotBatchesPendingCohort();

    Page<ScoringRun> findByKind(ScoringRun.Kind kind, Pageable pageable);

    /** Execuções em andamento do tipo (evita treino simultâneo). */
    boolean existsRunning(ScoringRun.Kind kind);

    /** Execuções em andamento iniciadas antes do instante (interrompidas: job esgotou as tentativas). */
    List<ScoringRun> findRunningStartedBefore(java.time.Instant before);
}
