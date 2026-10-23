package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.ContactPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContactPolicyRepository {

    ContactPolicy save(ContactPolicy policy);

    /** Versão vigente no instante: maior {@code effectiveFrom} já alcançado (credor nulo = assessoria). */
    Optional<ContactPolicy> findEffective(UUID creditorId, Instant at);

    /** Todas as versões, da mais recente para a mais antiga. */
    List<ContactPolicy> findHistory(UUID creditorId);

    /** Maior número de versão publicado (0 se nenhuma). */
    int maxVersion(UUID creditorId);
}
