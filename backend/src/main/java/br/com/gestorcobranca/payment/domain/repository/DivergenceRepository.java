package br.com.gestorcobranca.payment.domain.repository;

import br.com.gestorcobranca.payment.domain.model.Divergence;
import br.com.gestorcobranca.payment.domain.model.DivergenceStatus;
import br.com.gestorcobranca.payment.domain.model.DivergenceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface DivergenceRepository {

    Divergence save(Divergence divergence);

    Optional<Divergence> findById(UUID id);

    Optional<Divergence> findByIdForUpdate(UUID id);

    boolean existsByTypeAndProviderReference(DivergenceType type, String providerReference);

    /**
     * @param creditorIds credores permitidos (nulo = todos, inclusive divergências sem credor)
     */
    Page<Divergence> search(Set<UUID> creditorIds, Criteria criteria, Pageable pageable);

    /**
     * @param statuses      situações (vazio ou nulo = todas)
     * @param creditorId    credor escolhido (nulo = qualquer)
     * @param openedFrom    abertas a partir do instante (inclusive); nulo = sem limite
     * @param openedBefore  abertas antes do instante (exclusivo); nulo = sem limite
     */
    record Criteria(Set<DivergenceStatus> statuses, DivergenceType type, UUID creditorId, Instant openedFrom,
                    Instant openedBefore) {
    }
}
