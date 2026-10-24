package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.telephony.domain.model.Agent;
import br.com.gestorcobranca.telephony.domain.model.PresenceStatus;
import br.com.gestorcobranca.telephony.domain.repository.AgentRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Distribuição (ACD) da chamada atendida: o operador disponível há mais tempo
 * ({@code last_available_at}) — na campanha, para o discador; o responsável pelo caso, se
 * disponível, ou qualquer disponível, para chamadas recebidas. A escolha bloqueia a linha do
 * operador com {@code SKIP LOCKED}: duas chamadas atendidas ao mesmo tempo nunca vão ao mesmo
 * operador, e a reserva ({@code ON_CALL}) acontece na mesma transação.
 */
@Component
class CallDistributor {

    private final AgentRepository agents;
    private final PresenceRecorder presence;

    CallDistributor(AgentRepository agents, PresenceRecorder presence) {
        this.agents = agents;
        this.presence = presence;
    }

    Optional<Agent> forCampaign(UUID campaignId, UUID callId, Instant now) {
        return reserve(agents.lockAvailableIdsInCampaign(campaignId, 1), callId, now);
    }

    Optional<Agent> forInbound(UUID preferredUserId, UUID callId, Instant now) {
        if (preferredUserId != null) {
            Optional<Agent> preferred = agents.findByUserIdForUpdate(preferredUserId)
                    .filter(a -> a.isActive() && a.getStatus() == PresenceStatus.AVAILABLE);
            if (preferred.isPresent()) {
                return Optional.of(reserve(preferred.get(), callId, now));
            }
        }
        return reserve(agents.lockAvailableIds(1), callId, now);
    }

    private Optional<Agent> reserve(List<UUID> ids, UUID callId, Instant now) {
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return agents.findAllByIdIn(ids).stream().findFirst().map(a -> reserve(a, callId, now));
    }

    private Agent reserve(Agent agent, UUID callId, Instant now) {
        presence.record(agent.reserve(callId, now), "call");
        return agents.save(agent);
    }
}
