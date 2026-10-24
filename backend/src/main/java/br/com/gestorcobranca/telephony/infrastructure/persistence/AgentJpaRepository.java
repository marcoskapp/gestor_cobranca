package br.com.gestorcobranca.telephony.infrastructure.persistence;

import br.com.gestorcobranca.telephony.domain.model.Agent;
import br.com.gestorcobranca.telephony.domain.repository.AgentRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AgentJpaRepository extends JpaRepository<Agent, UUID>, AgentRepository {

    @Override
    Optional<Agent> findByUserId(UUID userId);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Agent a where a.userId = :userId")
    Optional<Agent> findByUserIdForUpdate(UUID userId);

    @Override
    @Query(value = """
            select id from telephony.agent
             where status = 'AVAILABLE' and active and campaign_id = :campaignId
             order by last_available_at nulls first, id limit :limit
               for update skip locked
            """, nativeQuery = true)
    List<UUID> lockAvailableIdsInCampaign(UUID campaignId, int limit);

    @Override
    @Query(value = """
            select id from telephony.agent
             where status = 'AVAILABLE' and active
             order by last_available_at nulls first, id limit :limit
               for update skip locked
            """, nativeQuery = true)
    List<UUID> lockAvailableIds(int limit);

    @Override
    @Query(value = """
            select count(*) from telephony.agent where status = 'AVAILABLE' and active and campaign_id = :campaignId
            """, nativeQuery = true)
    long countAvailableInCampaign(UUID campaignId);

    @Override
    @Query(value = """
            select id from telephony.agent
             where status <> 'OFFLINE' and coalesce(last_heartbeat_at, status_since) < :cutoff
             order by id limit :limit
            """, nativeQuery = true)
    List<UUID> findLoggedInWithoutHeartbeatSince(Instant cutoff, int limit);
}
