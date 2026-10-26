package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.telephony.domain.model.Agent;
import br.com.gestorcobranca.telephony.domain.model.AgentEndpoint;
import br.com.gestorcobranca.telephony.domain.repository.AgentEndpointRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ramal do operador por conta: o específico da conta, se ativo; nenhum, se marcado como fora da
 * plataforma; o ramal padrão do operador, se não há registro para a conta.
 */
@Component
class AgentEndpoints {

    private final AgentEndpointRepository endpoints;

    AgentEndpoints(AgentEndpointRepository endpoints) {
        this.endpoints = endpoints;
    }

    Optional<String> extensionFor(Agent agent, UUID accountId) {
        Optional<AgentEndpoint> specific = endpoints.findByAgentIdAndAccountId(agent.getId(), accountId);
        if (specific.isPresent()) {
            return specific.get().isActive() ? Optional.of(specific.get().getExtension()) : Optional.empty();
        }
        return Optional.of(agent.getExtension());
    }

    /** Contas em que o operador tem ramal específico ativo (por ordem de cadastro). */
    List<UUID> specificAccounts(Agent agent) {
        return endpoints.findAllByAgentId(agent.getId()).stream().filter(AgentEndpoint::isActive)
                .sorted(java.util.Comparator.comparing(AgentEndpoint::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .map(AgentEndpoint::getAccountId).toList();
    }
}
