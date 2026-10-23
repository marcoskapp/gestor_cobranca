package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.CreditorAuthorization;
import br.com.gestorcobranca.legal.domain.repository.CreditorAuthorizationRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface CreditorAuthorizationJpaRepository extends JpaRepository<CreditorAuthorization, UUID>,
        CreditorAuthorizationRepository {

    @Override
    Optional<CreditorAuthorization> findById(UUID id);

    @Override
    void flush();
}
