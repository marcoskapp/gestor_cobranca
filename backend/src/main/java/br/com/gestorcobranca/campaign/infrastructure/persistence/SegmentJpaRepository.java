package br.com.gestorcobranca.campaign.infrastructure.persistence;

import br.com.gestorcobranca.campaign.domain.model.Segment;
import br.com.gestorcobranca.campaign.domain.repository.SegmentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.UUID;

interface SegmentJpaRepository extends JpaRepository<Segment, UUID>, JpaSpecificationExecutor<Segment>, SegmentRepository {

    @Override
    default Page<Segment> search(Collection<UUID> creditorIds, boolean includeTenantWide, UUID creditorId, Boolean active,
                                 Pageable pageable) {
        Specification<Segment> spec = Specification.unrestricted();
        if (creditorIds != null) {
            spec = spec.and((root, query, cb) -> {
                var inScope = creditorIds.isEmpty() ? cb.disjunction() : root.get("creditorId").in(creditorIds);
                return includeTenantWide ? cb.or(inScope, cb.isNull(root.get("creditorId"))) : inScope;
            });
        } else if (!includeTenantWide) {
            spec = spec.and((root, query, cb) -> cb.isNotNull(root.get("creditorId")));
        }
        if (creditorId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("creditorId"), creditorId));
        }
        if (active != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        return findAll(spec, pageable);
    }
}
