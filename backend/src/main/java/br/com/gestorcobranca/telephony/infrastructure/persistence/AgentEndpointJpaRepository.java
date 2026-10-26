package br.com.gestorcobranca.telephony.infrastructure.persistence;

import br.com.gestorcobranca.telephony.domain.model.AgentEndpoint;
import br.com.gestorcobranca.telephony.domain.repository.AgentEndpointRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface AgentEndpointJpaRepository extends JpaRepository<AgentEndpoint, UUID>, AgentEndpointRepository {
}
