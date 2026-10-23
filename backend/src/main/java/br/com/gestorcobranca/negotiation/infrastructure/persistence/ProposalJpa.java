package br.com.gestorcobranca.negotiation.infrastructure.persistence;

import br.com.gestorcobranca.negotiation.domain.model.proposal.Proposal;
import br.com.gestorcobranca.negotiation.domain.model.proposal.ProposalStatus;
import br.com.gestorcobranca.negotiation.domain.repository.ProposalRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface ProposalJpa extends JpaRepository<Proposal, UUID>, JpaSpecificationExecutor<Proposal>, ProposalRepository {

    @Override
    default Page<Proposal> search(Collection<UUID> creditorIds, UUID creditorId, UUID debtorId, UUID caseId,
                                  ProposalStatus status,
                                  String actorType, UUID actorId, Pageable page) {
        Specification<Proposal> spec = Specification.unrestricted();
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
        if (actorType != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("actorType"), actorType));
        }
        if (actorId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("actorId"), actorId));
        }
        return findAll(spec, page);
    }

    @Override
    default List<Proposal> findPendingApproval(Collection<UUID> creditorIds, int limit) {
        Specification<Proposal> spec = (root, q, cb) -> cb.equal(root.get("status"), ProposalStatus.PENDING_APPROVAL);
        if (creditorIds != null) {
            spec = spec.and((root, q, cb) -> creditorIds.isEmpty() ? cb.disjunction() : root.get("creditorId").in(creditorIds));
        }
        return findAll(spec, PageRequest.of(0, limit, Sort.by("createdAt", "id"))).getContent();
    }

    @Override
    @Query("""
            select p from Proposal p
            where p.status in (br.com.gestorcobranca.negotiation.domain.model.proposal.ProposalStatus.DRAFT,
                               br.com.gestorcobranca.negotiation.domain.model.proposal.ProposalStatus.PENDING_APPROVAL,
                               br.com.gestorcobranca.negotiation.domain.model.proposal.ProposalStatus.APPROVED,
                               br.com.gestorcobranca.negotiation.domain.model.proposal.ProposalStatus.ACCEPTED)
              and p.validUntil < :today
            order by p.validUntil, p.id
            limit :limit
            """)
    List<Proposal> findOpenExpiredBefore(@Param("today") LocalDate today, @Param("limit") int limit);

    @Override
    List<Proposal> findByDebtorId(UUID debtorId);
}
