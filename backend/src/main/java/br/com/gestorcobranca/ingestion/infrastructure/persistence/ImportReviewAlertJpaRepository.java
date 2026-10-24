package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.domain.model.review.AlertStatus;
import br.com.gestorcobranca.ingestion.domain.model.review.AlertType;
import br.com.gestorcobranca.ingestion.domain.model.review.ImportReviewAlert;
import br.com.gestorcobranca.ingestion.domain.repository.ImportReviewAlertRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface ImportReviewAlertJpaRepository extends JpaRepository<ImportReviewAlert, UUID>,
        JpaSpecificationExecutor<ImportReviewAlert>, ImportReviewAlertRepository {

    @Override
    default Page<ImportReviewAlert> search(UUID batchId, AlertType type, AlertStatus status, Pageable pageable) {
        Specification<ImportReviewAlert> spec = (root, query, cb) -> cb.equal(root.get("batchId"), batchId);
        if (type != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), type));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return findAll(spec, pageable);
    }

    @Override
    @Query("""
            select distinct a.rowNumber from ImportReviewAlert a
            where a.batchId = :batchId and a.rowNumber > 0
              and a.status = br.com.gestorcobranca.ingestion.domain.model.review.AlertStatus.EXCLUDED
            order by a.rowNumber
            """)
    List<Long> excludedRows(UUID batchId);

    @Query("""
            select a.type, a.status, count(a) from ImportReviewAlert a
            where a.batchId = :batchId group by a.type, a.status
            """)
    List<Object[]> countGroupedByType(UUID batchId);

    @Override
    default List<TypeCount> countByType(UUID batchId) {
        return countGroupedByType(batchId).stream()
                .map(r -> new TypeCount((AlertType) r[0], (AlertStatus) r[1], ((Number) r[2]).longValue()))
                .toList();
    }
}
