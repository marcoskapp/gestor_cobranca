package br.com.gestorcobranca.negotiation.infrastructure.persistence;

import br.com.gestorcobranca.negotiation.domain.model.policy.CollectionPolicy;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyStatus;
import br.com.gestorcobranca.negotiation.domain.repository.CollectionPolicyRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface CollectionPolicyJpaRepository extends JpaRepository<CollectionPolicy, UUID>,
        JpaSpecificationExecutor<CollectionPolicy>, CollectionPolicyRepository {

    @Override
    @Query("select p from CollectionPolicy p where p.creditorId = :creditorId order by p.policyVersion desc")
    List<CollectionPolicy> findByCreditor(@Param("creditorId") UUID creditorId);

    @Override
    @Query("""
            select p from CollectionPolicy p
            where p.creditorId = :creditorId
              and p.status <> br.com.gestorcobranca.negotiation.domain.model.policy.PolicyStatus.DRAFT
            order by p.policyVersion desc
            """)
    List<CollectionPolicy> findEffectiveCandidates(@Param("creditorId") UUID creditorId);

    @Override
    @Query("select coalesce(max(p.policyVersion), 0) from CollectionPolicy p where p.creditorId = :creditorId")
    int maxVersion(@Param("creditorId") UUID creditorId);

    @Override
    default Page<CollectionPolicy> search(Collection<UUID> creditorIds, UUID creditorId, PolicyStatus status,
                                          Pageable page) {
        Specification<CollectionPolicy> spec = Specification.unrestricted();
        if (creditorIds != null) {
            spec = spec.and((root, query, cb) -> creditorIds.isEmpty() ? cb.disjunction()
                    : root.get("creditorId").in(creditorIds));
        }
        if (creditorId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("creditorId"), creditorId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return findAll(spec, page);
    }
}
