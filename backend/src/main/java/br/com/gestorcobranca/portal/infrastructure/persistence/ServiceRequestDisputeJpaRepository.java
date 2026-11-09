package br.com.gestorcobranca.portal.infrastructure.persistence;

import br.com.gestorcobranca.portal.domain.model.ServiceRequestDispute;
import br.com.gestorcobranca.portal.domain.repository.ServiceRequestDisputeRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ServiceRequestDisputeJpaRepository extends JpaRepository<ServiceRequestDispute, UUID>,
        ServiceRequestDisputeRepository {
}
