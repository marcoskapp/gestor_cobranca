package br.com.gestorcobranca.scoring.infrastructure.persistence;

import br.com.gestorcobranca.scoring.domain.model.ScoringRun;
import br.com.gestorcobranca.scoring.domain.repository.ScoringRunRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ScoringRunJpaRepository extends JpaRepository<ScoringRun, UUID>, ScoringRunRepository {

    @Override
    default Optional<ScoringRun> findBatch(LocalDate referenceDate) {
        return findFirstByKindAndReferenceDate(ScoringRun.Kind.BATCH, referenceDate);
    }

    Optional<ScoringRun> findFirstByKindAndReferenceDate(ScoringRun.Kind kind, LocalDate referenceDate);

    @Override
    default Optional<ScoringRun> findLatestBatch() {
        return findFirstByKindOrderByReferenceDateDesc(ScoringRun.Kind.BATCH);
    }

    Optional<ScoringRun> findFirstByKindOrderByReferenceDateDesc(ScoringRun.Kind kind);

    @Override
    @Query("""
            select r from ScoringRun r
             where r.status = br.com.gestorcobranca.scoring.domain.model.ScoringRun.Status.RUNNING
               and r.startedAt < :before
            """)
    List<ScoringRun> findRunningStartedBefore(java.time.Instant before);

    @Override
    @Query("""
            select r from ScoringRun r
             where r.kind = br.com.gestorcobranca.scoring.domain.model.ScoringRun.Kind.BATCH and r.snapshot = true
               and r.status = br.com.gestorcobranca.scoring.domain.model.ScoringRun.Status.COMPLETED
               and r.cohortMeasuredAt is null
             order by r.referenceDate
            """)
    List<ScoringRun> findSnapshotBatchesPendingCohort();

    @Override
    Page<ScoringRun> findByKind(ScoringRun.Kind kind, Pageable pageable);

    @Override
    default boolean existsRunning(ScoringRun.Kind kind) {
        return existsByKindAndStatus(kind, ScoringRun.Status.RUNNING);
    }

    boolean existsByKindAndStatus(ScoringRun.Kind kind, ScoringRun.Status status);
}
