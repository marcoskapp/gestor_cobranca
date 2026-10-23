package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.domain.model.ImportLayout;
import br.com.gestorcobranca.ingestion.domain.repository.ImportLayoutRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Sort;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface ImportLayoutJpaRepository extends JpaRepository<ImportLayout, UUID>, JpaSpecificationExecutor<ImportLayout>,
        ImportLayoutRepository {

    @Override
    @Query("select coalesce(max(l.layoutVersion), 0) from ImportLayout l where l.creditorId = :creditorId and l.code = :code")
    int maxVersion(UUID creditorId, String code);

    @Override
    default List<ImportLayout> list(Collection<UUID> creditorIds, UUID creditorId, boolean includeInactive) {
        Specification<ImportLayout> spec = Specification.unrestricted();
        if (creditorIds != null) {
            spec = spec.and((root, query, cb) -> creditorIds.isEmpty() ? cb.disjunction() : root.get("creditorId").in(creditorIds));
        }
        if (creditorId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("creditorId"), creditorId));
        }
        if (!includeInactive) {
            spec = spec.and((root, query, cb) -> cb.isTrue(root.get("active")));
        }
        return findAll(spec, Sort.by("creditorId", "code").and(Sort.by(Sort.Direction.DESC, "layoutVersion")));
    }
}
