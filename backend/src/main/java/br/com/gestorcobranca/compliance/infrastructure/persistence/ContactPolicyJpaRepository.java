package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.domain.model.ContactPolicy;
import br.com.gestorcobranca.compliance.domain.repository.ContactPolicyRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ContactPolicyJpaRepository extends JpaRepository<ContactPolicy, UUID>, ContactPolicyRepository {

    @Override
    default Optional<ContactPolicy> findEffective(UUID creditorId, Instant at) {
        List<ContactPolicy> found = creditorId == null ? findTenantEffective(at, Limit.of(1))
                : findCreditorEffective(creditorId, at, Limit.of(1));
        return found.stream().findFirst();
    }

    @Query("""
            select p from ContactPolicy p where p.creditorId is null and p.effectiveFrom <= :at
             order by p.effectiveFrom desc, p.policyVersion desc
            """)
    List<ContactPolicy> findTenantEffective(Instant at, Limit limit);

    @Query("""
            select p from ContactPolicy p where p.creditorId = :creditorId and p.effectiveFrom <= :at
             order by p.effectiveFrom desc, p.policyVersion desc
            """)
    List<ContactPolicy> findCreditorEffective(UUID creditorId, Instant at, Limit limit);

    @Override
    default List<ContactPolicy> findHistory(UUID creditorId) {
        return creditorId == null ? findTenantHistory() : findCreditorHistory(creditorId);
    }

    @Query("select p from ContactPolicy p where p.creditorId is null order by p.policyVersion desc")
    List<ContactPolicy> findTenantHistory();

    @Query("select p from ContactPolicy p where p.creditorId = :creditorId order by p.policyVersion desc")
    List<ContactPolicy> findCreditorHistory(UUID creditorId);

    @Override
    default int maxVersion(UUID creditorId) {
        return creditorId == null ? maxTenantVersion() : maxCreditorVersion(creditorId);
    }

    @Query("select coalesce(max(p.policyVersion), 0) from ContactPolicy p where p.creditorId is null")
    int maxTenantVersion();

    @Query("select coalesce(max(p.policyVersion), 0) from ContactPolicy p where p.creditorId = :creditorId")
    int maxCreditorVersion(UUID creditorId);
}
