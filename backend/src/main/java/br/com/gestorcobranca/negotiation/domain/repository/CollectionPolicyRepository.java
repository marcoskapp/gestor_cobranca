package br.com.gestorcobranca.negotiation.domain.repository;

import br.com.gestorcobranca.negotiation.domain.model.policy.CollectionPolicy;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Versões de políticas de cobrança por credor. */
public interface CollectionPolicyRepository {

    CollectionPolicy save(CollectionPolicy policy);

    Optional<CollectionPolicy> findById(UUID id);

    /** Versões do credor, da mais recente para a mais antiga. */
    List<CollectionPolicy> findByCreditor(UUID creditorId);

    /**
     * Versões publicadas ou aposentadas do credor (candidatas a vigência), da mais recente para
     * a mais antiga.
     */
    List<CollectionPolicy> findEffectiveCandidates(UUID creditorId);

    int maxVersion(UUID creditorId);

    /**
     * @param creditorIds credores permitidos (nulo = todos)
     * @param creditorId  filtro opcional
     */
    Page<CollectionPolicy> search(Collection<UUID> creditorIds, UUID creditorId, PolicyStatus status, Pageable page);
}
