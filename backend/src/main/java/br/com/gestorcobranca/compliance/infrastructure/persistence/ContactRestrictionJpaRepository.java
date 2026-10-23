package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.domain.model.ContactRestriction;
import br.com.gestorcobranca.compliance.domain.repository.ContactRestrictionRepository;
import br.com.gestorcobranca.compliance.domain.repository.RestrictionSearchCriteria;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface ContactRestrictionJpaRepository extends JpaRepository<ContactRestriction, UUID>,
        JpaSpecificationExecutor<ContactRestriction>, ContactRestrictionRepository {

    @Override
    @Query("""
            select r from ContactRestriction r
             where r.status = br.com.gestorcobranca.compliance.domain.model.RestrictionStatus.ACTIVE
               and (r.debtorId = :debtorId
                    or (:contactValueHash is not null and r.contactValueHash = :contactValueHash
                        and (r.debtorId is null
                             or r.type = br.com.gestorcobranca.compliance.domain.model.RestrictionType.OPT_OUT)))
            """)
    List<ContactRestriction> findActiveForGate(UUID debtorId, String contactValueHash);

    @Override
    @Query("""
            select r from ContactRestriction r
             where r.status = br.com.gestorcobranca.compliance.domain.model.RestrictionStatus.ACTIVE
               and r.contactValueHash = :contactValueHash
            """)
    List<ContactRestriction> findActiveByContactValueHash(String contactValueHash);

    @Override
    List<ContactRestriction> findByDebtorIdOrderByCreatedAtAsc(UUID debtorId);

    @Override
    default List<ContactRestriction> findExpiredUnnotified(Instant now, int limit) {
        return findExpiredUnnotified(now, Limit.of(limit));
    }

    @Query("""
            select r from ContactRestriction r
             where r.status = br.com.gestorcobranca.compliance.domain.model.RestrictionStatus.ACTIVE
               and r.endsAt is not null and r.endsAt <= :now and r.expiryNotifiedAt is null
             order by r.endsAt
            """)
    List<ContactRestriction> findExpiredUnnotified(Instant now, Limit limit);

    @Override
    default Page<ContactRestriction> search(RestrictionSearchCriteria c, Pageable pageable) {
        Specification<ContactRestriction> spec = Specification.unrestricted();
        if (c.allowedCreditorIds() != null) {
            spec = spec.and((root, query, cb) -> {
                var inScope = c.allowedCreditorIds().isEmpty() ? cb.disjunction()
                        : root.get("creditorId").in(c.allowedCreditorIds());
                return cb.or(cb.isNull(root.get("creditorId")), inScope);
            });
        }
        if (c.debtorId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("debtorId"), c.debtorId()));
        }
        if (c.creditorId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("creditorId"), c.creditorId()));
        }
        if (c.type() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), c.type()));
        }
        if (c.status() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), c.status()));
        }
        if (c.channel() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("channel"), c.channel()));
        }
        return findAll(spec, pageable);
    }
}
