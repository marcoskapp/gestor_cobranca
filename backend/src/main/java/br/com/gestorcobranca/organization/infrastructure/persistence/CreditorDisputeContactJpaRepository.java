package br.com.gestorcobranca.organization.infrastructure.persistence;

import br.com.gestorcobranca.organization.domain.model.CreditorDisputeContact;
import br.com.gestorcobranca.organization.domain.repository.CreditorDisputeContactRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface CreditorDisputeContactJpaRepository extends JpaRepository<CreditorDisputeContact, UUID>,
        CreditorDisputeContactRepository {
}
