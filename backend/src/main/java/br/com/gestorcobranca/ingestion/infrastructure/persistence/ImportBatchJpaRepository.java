package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.domain.model.ImportBatch;
import br.com.gestorcobranca.ingestion.domain.repository.ImportBatchRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.UUID;

interface ImportBatchJpaRepository extends JpaRepository<ImportBatch, UUID>, JpaSpecificationExecutor<ImportBatch>,
        ImportBatchRepository {

    @Override
    default Page<ImportBatch> search(Collection<UUID> allowedCreditorIds, Criteria criteria, Pageable pageable) {
        Specification<ImportBatch> spec = Specification.unrestricted();
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
        if (criteria.status() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), criteria.status()));
        }
        if (criteria.source() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("source"), criteria.source()));
        }
        if (criteria.createdFrom() != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), criteria.createdFrom()));
        }
        if (criteria.createdTo() != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("createdAt"), criteria.createdTo()));
        }
        return findAll(spec, pageable);
    }
}
