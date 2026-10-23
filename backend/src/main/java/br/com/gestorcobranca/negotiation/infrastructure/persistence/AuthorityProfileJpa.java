package br.com.gestorcobranca.negotiation.infrastructure.persistence;

import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface AuthorityProfileJpa extends JpaRepository<AuthorityProfile, UUID> {

    Optional<AuthorityProfile> findByCode(String code);

    boolean existsByCode(String code);
}
