package br.com.gestorcobranca.publicapi.infrastructure.persistence;

import br.com.gestorcobranca.publicapi.domain.model.IntegrationToken;
import br.com.gestorcobranca.publicapi.domain.model.TokenStatus;
import br.com.gestorcobranca.publicapi.domain.repository.IntegrationTokenRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface IntegrationTokenJpaRepository extends JpaRepository<IntegrationToken, UUID>,
        JpaSpecificationExecutor<IntegrationToken>, IntegrationTokenRepository {

    @Override
    Optional<IntegrationToken> findByPrefix(String prefix);

    @Override
    List<IntegrationToken> findAllByIdIn(Collection<UUID> ids);

    @Override
    @Query("select distinct t.creditorId from IntegrationToken t order by t.creditorId")
    List<UUID> findDistinctCreditorIds();

    @Override
    default Page<IntegrationToken> search(Collection<UUID> allowedCreditorIds, UUID creditorId, TokenStatus status,
                                          Pageable pageable) {
        Specification<IntegrationToken> spec = Specification.unrestricted();
        if (allowedCreditorIds != null) {
            spec = spec.and((root, q, cb) -> allowedCreditorIds.isEmpty()
                    ? cb.disjunction() : root.get("creditorId").in(allowedCreditorIds));
        }
        if (creditorId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("creditorId"), creditorId));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        return findAll(spec, pageable);
    }
}
