package br.com.gestorcobranca.settlement.infrastructure.persistence;

import br.com.gestorcobranca.settlement.domain.model.RatingStatus;
import br.com.gestorcobranca.settlement.domain.model.Receipt;
import br.com.gestorcobranca.settlement.domain.repository.ReceiptRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

interface ReceiptJpaRepository extends JpaRepository<Receipt, UUID>, JpaSpecificationExecutor<Receipt>,
        ReceiptRepository {

    @Query(value = "select max(paid_on) from settlement.receipt where contract_id = :contractId "
            + "and kind = 'PAYMENT' and rating_status = 'RATED'", nativeQuery = true)
    LocalDate maxRatedCompetence(@Param("contractId") UUID contractId);

    @Override
    default Optional<LocalDate> lastRatedCompetence(UUID contractId) {
        return Optional.ofNullable(maxRatedCompetence(contractId));
    }

    @Override
    default Page<Receipt> search(Collection<UUID> creditorIds, UUID creditorId, UUID agreementId, LocalDate from,
                                 LocalDate to, RatingStatus ratingStatus, Boolean inStatement, Pageable pageable) {
        Specification<Receipt> spec = Specification.unrestricted();
        if (creditorIds != null) {
            spec = spec.and((root, q, cb) -> creditorIds.isEmpty() ? cb.disjunction()
                    : root.get("creditorId").in(creditorIds));
        }
        if (creditorId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("creditorId"), creditorId));
        }
        if (agreementId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("agreementId"), agreementId));
        }
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("competenceDate"), from));
        }
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("competenceDate"), to));
        }
        if (ratingStatus != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("ratingStatus"), ratingStatus));
        }
        if (inStatement != null) {
            spec = spec.and((root, q, cb) -> inStatement ? cb.isNotNull(root.get("statementId"))
                    : cb.isNull(root.get("statementId")));
        }
        return findAll(spec, pageable);
    }
}
