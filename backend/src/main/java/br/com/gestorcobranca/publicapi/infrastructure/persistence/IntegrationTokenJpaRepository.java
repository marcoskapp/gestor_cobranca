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

import java.time.Instant;
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
    List<IntegrationToken> findAll();

    @Override
    @Query("""
            select t from IntegrationToken t
             where t.status = br.com.gestorcobranca.publicapi.domain.model.TokenStatus.PAUSED
               and t.resumeAt is not null and t.resumeAt <= :now
             order by t.resumeAt, t.id""")
    List<IntegrationToken> findResumeDue(Instant now);

    @Override
    @Query("select distinct t.creditorId from IntegrationToken t order by t.creditorId")
    List<UUID> findDistinctCreditorIds();

    @Override
    default Page<IntegrationToken> search(Collection<UUID> allowedCreditorIds, UUID creditorId,
                                          Collection<TokenStatus> statuses, Pageable pageable) {
        Specification<IntegrationToken> spec = Specification.unrestricted();
        if (allowedCreditorIds != null) {
            spec = spec.and((root, q, cb) -> allowedCreditorIds.isEmpty()
                    ? cb.disjunction() : root.get("creditorId").in(allowedCreditorIds));
        }
        if (creditorId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("creditorId"), creditorId));
        }
        if (statuses != null) {
            spec = spec.and((root, q, cb) -> statuses.isEmpty() ? cb.disjunction() : root.get("status").in(statuses));
        }
        return findAll(spec, pageable);
    }
}
