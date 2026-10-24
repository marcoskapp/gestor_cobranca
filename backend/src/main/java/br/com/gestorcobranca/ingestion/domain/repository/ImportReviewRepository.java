package br.com.gestorcobranca.ingestion.domain.repository;

import br.com.gestorcobranca.ingestion.domain.model.review.ImportReview;
import br.com.gestorcobranca.ingestion.domain.model.review.ReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ImportReviewRepository {

    ImportReview save(ImportReview review);

    Optional<ImportReview> findById(UUID id);

    /**
     * @param allowedCreditorIds credores visíveis ao ator; {@code null} = sem restrição
     */
    Page<ImportReview> search(Collection<UUID> allowedCreditorIds, Criteria criteria, Pageable pageable);

    /** Filtros opcionais. */
    record Criteria(UUID creditorId, UUID portfolioId, Collection<ReviewStatus> statuses) {
    }
}
