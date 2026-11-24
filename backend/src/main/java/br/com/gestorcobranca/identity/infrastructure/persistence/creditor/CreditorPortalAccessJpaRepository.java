package br.com.gestorcobranca.identity.infrastructure.persistence.creditor;

import br.com.gestorcobranca.identity.domain.model.creditor.CreditorPortalAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface CreditorPortalAccessJpaRepository extends JpaRepository<CreditorPortalAccess, UUID> {

    Optional<CreditorPortalAccess> findByCreditorId(UUID creditorId);
}
