package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImport;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImportStatus;
import br.com.gestorcobranca.ingestion.domain.repository.PdfImportRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PdfImportJpaRepository extends JpaRepository<PdfImport, UUID>, JpaSpecificationExecutor<PdfImport>,
        PdfImportRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PdfImport p where p.id = :id")
    Optional<PdfImport> findByIdForUpdate(@Param("id") UUID id);

    @Override
    @Query("""
            select p from PdfImport p
             where p.status in :statuses and p.fileKey is not null and p.updatedAt < :updatedBefore
             order by p.updatedAt
            """)
    List<PdfImport> findRetentionCandidates(@Param("statuses") Collection<PdfImportStatus> statuses,
                                            @Param("updatedBefore") Instant updatedBefore, Pageable page);

    @Override
    default Page<PdfImport> search(Collection<UUID> allowedCreditorIds, Criteria criteria, Pageable pageable) {
        Specification<PdfImport> spec = Specification.unrestricted();
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
