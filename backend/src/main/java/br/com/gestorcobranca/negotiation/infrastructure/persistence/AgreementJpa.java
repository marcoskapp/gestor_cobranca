package br.com.gestorcobranca.negotiation.infrastructure.persistence;

import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementPayment;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementStatus;
import br.com.gestorcobranca.negotiation.domain.repository.AgreementRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AgreementJpa extends JpaRepository<Agreement, UUID>, JpaSpecificationExecutor<Agreement> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Agreement a where a.id = :id")
    Optional<Agreement> lockById(@Param("id") UUID id);

    Optional<Agreement> findByProposalId(UUID proposalId);

    @Query("""
            select distinct a.id from Agreement a join a.installments i
            where a.status in (br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementStatus.FORMALIZED,
                               br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementStatus.IN_PAYMENT)
              and i.status <> br.com.gestorcobranca.negotiation.domain.model.agreement.InstallmentStatus.PAID
              and i.dueDate < :date
              and a.id > :afterId
            order by a.id
            limit :limit
            """)
    List<UUID> activeIdsWithDueBefore(@Param("date") LocalDate date, @Param("afterId") UUID afterId,
                                      @Param("limit") int limit);

    @Query("""
            select distinct a from Agreement a join a.titles t
            where t.active = true and t.titleId in :titleIds
            """)
    List<Agreement> activeCovering(@Param("titleIds") Collection<UUID> titleIds);

    List<Agreement> findByDebtorId(UUID debtorId);
}

interface AgreementPaymentJpa extends JpaRepository<AgreementPayment, UUID> {

    Optional<AgreementPayment> findByAgreementIdAndPaymentId(UUID agreementId, UUID paymentId);

    List<AgreementPayment> findByPaymentId(UUID paymentId);

    List<AgreementPayment> findByAgreementIdOrderByCreatedAtAsc(UUID agreementId);
}

@Repository
class JpaAgreementRepository implements AgreementRepository {

    private final AgreementJpa agreements;
    private final AgreementPaymentJpa payments;

    JpaAgreementRepository(AgreementJpa agreements, AgreementPaymentJpa payments) {
        this.agreements = agreements;
        this.payments = payments;
    }

    @Override
    public Agreement save(Agreement agreement) {
        return agreements.save(agreement);
    }

    @Override
    public Agreement saveAndFlush(Agreement agreement) {
        return agreements.saveAndFlush(agreement);
    }

    @Override
    public Optional<Agreement> findById(UUID id) {
        return agreements.findById(id);
    }

    @Override
    public Optional<Agreement> findByIdForUpdate(UUID id) {
        return agreements.lockById(id);
    }

    @Override
    public Optional<Agreement> findByProposalId(UUID proposalId) {
        return agreements.findByProposalId(proposalId);
    }

    @Override
    public Page<Agreement> search(Collection<UUID> creditorIds, UUID creditorId, UUID debtorId, UUID caseId,
                                  AgreementStatus status, Pageable page) {
        Specification<Agreement> spec = Specification.unrestricted();
        if (creditorIds != null) {
            spec = spec.and((root, q, cb) -> creditorIds.isEmpty() ? cb.disjunction() : root.get("creditorId").in(creditorIds));
        }
        if (creditorId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("creditorId"), creditorId));
        }
        if (debtorId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("debtorId"), debtorId));
        }
        if (caseId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("caseId"), caseId));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        return agreements.findAll(spec, page);
    }

    @Override
    public List<UUID> findActiveIdsWithInstallmentDueBefore(LocalDate date, UUID afterId, int limit) {
        return agreements.activeIdsWithDueBefore(date, afterId == null ? new UUID(0L, 0L) : afterId, limit);
    }

    @Override
    public List<Agreement> findActiveCovering(Collection<UUID> titleIds) {
        return titleIds.isEmpty() ? List.of() : agreements.activeCovering(titleIds);
    }

    @Override
    public List<Agreement> findByDebtorId(UUID debtorId) {
        return agreements.findByDebtorId(debtorId);
    }

    @Override
    public AgreementPayment save(AgreementPayment payment) {
        return payments.save(payment);
    }

    @Override
    public Optional<AgreementPayment> findPayment(UUID agreementId, UUID paymentId) {
        return payments.findByAgreementIdAndPaymentId(agreementId, paymentId);
    }

    @Override
    public List<AgreementPayment> findPaymentsByPaymentId(UUID paymentId) {
        return payments.findByPaymentId(paymentId);
    }

    @Override
    public List<AgreementPayment> findPayments(UUID agreementId) {
        return payments.findByAgreementIdOrderByCreatedAtAsc(agreementId);
    }
}
