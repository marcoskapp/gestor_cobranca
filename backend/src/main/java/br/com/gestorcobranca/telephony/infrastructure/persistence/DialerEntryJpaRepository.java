package br.com.gestorcobranca.telephony.infrastructure.persistence;

import br.com.gestorcobranca.telephony.domain.model.DialerEntry;
import br.com.gestorcobranca.telephony.domain.repository.DialerEntryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

interface DialerEntryJpaRepository extends JpaRepository<DialerEntry, UUID>, DialerEntryRepository {

    @Override
    default Set<UUID> findCaseIdsInCampaign(UUID campaignId, Collection<UUID> caseIds) {
        return caseIds.isEmpty() ? Set.of() : new HashSet<>(caseIdsInCampaign(campaignId, caseIds));
    }

    @Query("select e.caseId from DialerEntry e where e.campaignId = :campaignId and e.caseId in :caseIds")
    List<UUID> caseIdsInCampaign(UUID campaignId, Collection<UUID> caseIds);

    @Override
    @Query(value = """
            select id from telephony.dialer_entry
             where campaign_id = :campaignId and status = 'PENDING' and next_attempt_at <= :now
             order by next_attempt_at, priority limit :limit
               for update skip locked
            """, nativeQuery = true)
    List<UUID> lockDueIds(UUID campaignId, Instant now, int limit);

    @Override
    default List<UUID> findPendingIds(UUID campaignId, UUID afterId, int limit) {
        return pendingIds(campaignId, afterId == null ? new UUID(0L, 0L) : afterId, limit);
    }

    @Query(value = """
            select id from telephony.dialer_entry
             where campaign_id = :campaignId and status = 'PENDING' and id > :afterId
             order by id limit :limit
            """, nativeQuery = true)
    List<UUID> pendingIds(UUID campaignId, UUID afterId, int limit);

    @Override
    @Query(value = "select count(*) from telephony.dialer_entry where campaign_id = :campaignId and status = :status",
            nativeQuery = true)
    long countByCampaignAndStatus(UUID campaignId, String status);

    @Override
    @Query(value = "select coalesce(max(priority), 0) from telephony.dialer_entry where campaign_id = :campaignId",
            nativeQuery = true)
    int maxPriority(UUID campaignId);

    @Override
    @Query(value = """
            select * from telephony.dialer_entry
             where campaign_id = :campaignId and (cast(:status as varchar) is null or status = cast(:status as varchar))
            """, countQuery = """
            select count(*) from telephony.dialer_entry
             where campaign_id = :campaignId and (cast(:status as varchar) is null or status = cast(:status as varchar))
            """, nativeQuery = true)
    Page<DialerEntry> findByCampaign(UUID campaignId, String status, Pageable pageable);
}
