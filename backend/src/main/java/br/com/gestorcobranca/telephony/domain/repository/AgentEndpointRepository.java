package br.com.gestorcobranca.telephony.domain.repository;

import br.com.gestorcobranca.telephony.domain.model.AgentEndpoint;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentEndpointRepository {

    AgentEndpoint save(AgentEndpoint endpoint);

    void delete(AgentEndpoint endpoint);

    Optional<AgentEndpoint> findByAgentIdAndAccountId(UUID agentId, UUID accountId);

    List<AgentEndpoint> findAllByAgentId(UUID agentId);

    List<AgentEndpoint> findAllByAgentIdIn(Collection<UUID> agentIds);
}
