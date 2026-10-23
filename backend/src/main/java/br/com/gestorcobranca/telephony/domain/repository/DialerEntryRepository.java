package br.com.gestorcobranca.telephony.domain.repository;

import br.com.gestorcobranca.telephony.domain.model.DialerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface DialerEntryRepository {

    DialerEntry save(DialerEntry entry);

    Optional<DialerEntry> findById(UUID id);

    List<DialerEntry> findAllByIdIn(Collection<UUID> ids);

    /** Casos já presentes na lista da campanha (entre os informados). */
    Set<UUID> findCaseIdsInCampaign(UUID campaignId, Collection<UUID> caseIds);

    /**
     * Itens pendentes vencidos, por prioridade, bloqueados com {@code FOR UPDATE SKIP LOCKED}.
     */
    List<UUID> lockDueIds(UUID campaignId, Instant now, int limit);

    /** Itens pendentes em ordem de id, a partir de {@code afterId} exclusive (nulo = início). */
    List<UUID> findPendingIds(UUID campaignId, UUID afterId, int limit);

    long countByCampaignAndStatus(UUID campaignId, String status);

    int maxPriority(UUID campaignId);

    Page<DialerEntry> findByCampaign(UUID campaignId, String status, Pageable pageable);
}
