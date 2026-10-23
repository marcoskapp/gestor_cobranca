package br.com.gestorcobranca.payment.domain.repository;

import br.com.gestorcobranca.payment.domain.model.Divergence;
import br.com.gestorcobranca.payment.domain.model.DivergenceStatus;
import br.com.gestorcobranca.payment.domain.model.DivergenceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface DivergenceRepository {

    Divergence save(Divergence divergence);

    Optional<Divergence> findById(UUID id);

    Optional<Divergence> findByIdForUpdate(UUID id);

    boolean existsByTypeAndProviderReference(DivergenceType type, String providerReference);

    /**
     * @param creditorIds          credores permitidos (nulo = todos, inclusive divergências sem credor)
     * @param statuses             situações (vazio = todas)
     */
    Page<Divergence> search(Set<UUID> creditorIds, Set<DivergenceStatus> statuses, DivergenceType type, Pageable pageable);
}
