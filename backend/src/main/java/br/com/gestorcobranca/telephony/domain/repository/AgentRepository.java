package br.com.gestorcobranca.telephony.domain.repository;

import br.com.gestorcobranca.telephony.domain.model.Agent;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentRepository {

    Agent save(Agent agent);

    Agent saveAndFlush(Agent agent);

    Optional<Agent> findById(UUID id);

    Optional<Agent> findByUserId(UUID userId);

    /** Com lock pessimista da linha (mudanças de presença e reserva para chamada). */
    Optional<Agent> findByUserIdForUpdate(UUID userId);

    List<Agent> findAllByIdIn(Collection<UUID> ids);

    List<Agent> findAllByOrderByStatusAscStatusSinceAsc();

    /**
     * Operadores disponíveis na campanha, livres há mais tempo primeiro, bloqueados com
     * {@code FOR UPDATE SKIP LOCKED} (distribuição concorrente sem disputa).
     */
    List<UUID> lockAvailableIdsInCampaign(UUID campaignId, int limit);

    /** Operadores disponíveis em qualquer campanha ou fora delas (chamadas recebidas), como acima. */
    List<UUID> lockAvailableIds(int limit);

    long countAvailableInCampaign(UUID campaignId);

    /** Conectados sem sinal do cliente desde o corte. */
    List<UUID> findLoggedInWithoutHeartbeatSince(Instant cutoff, int limit);
}
