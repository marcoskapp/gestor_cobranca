package br.com.gestorcobranca.ingestion.domain.repository;

import br.com.gestorcobranca.ingestion.domain.model.review.ImportTotalsCheck;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImportTotalsCheckRepository {

    ImportTotalsCheck save(ImportTotalsCheck check);

    /** Última conferência da prévia. */
    Optional<ImportTotalsCheck> findFirstByBatchIdOrderByCreatedAtDesc(UUID batchId);

    List<ImportTotalsCheck> findByReviewIdOrderByCreatedAtDesc(UUID reviewId);
}
