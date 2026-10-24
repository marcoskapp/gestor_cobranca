package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.domain.model.review.ImportReview;
import br.com.gestorcobranca.ingestion.domain.repository.ImportReviewRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.UUID;

interface ImportReviewJpaRepository extends JpaRepository<ImportReview, UUID>, JpaSpecificationExecutor<ImportReview>,
        ImportReviewRepository {

    @Override
    default Page<ImportReview> search(Collection<UUID> allowedCreditorIds, Criteria criteria, Pageable pageable) {
        Specification<ImportReview> spec = Specification.unrestricted();
        if (allowedCreditorIds != null) {
            spec = spec.and((root, query, cb) -> allowedCreditorIds.isEmpty()
                    ? cb.disjunction() : root.get("creditorId").in(allowedCreditorIds));
        }
        if (criteria.creditorId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("creditorId"), criteria.creditorId()));
        }
        if (criteria.portfolioId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("portfolioId"), criteria.portfolioId()));
        }
        if (criteria.statuses() != null && !criteria.statuses().isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("status").in(criteria.statuses()));
        }
        return findAll(spec, pageable);
    }
}
