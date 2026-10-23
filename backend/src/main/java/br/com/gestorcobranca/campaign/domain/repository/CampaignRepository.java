package br.com.gestorcobranca.campaign.domain.repository;

import br.com.gestorcobranca.campaign.domain.model.Campaign;
import br.com.gestorcobranca.campaign.domain.model.CampaignStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CampaignRepository {

    Campaign save(Campaign campaign);

    Optional<Campaign> findById(UUID id);

    /** Com lock pessimista (ativação/pausa concorrentes com o motor). */
    Optional<Campaign> findByIdForUpdate(UUID id);

    boolean existsByCode(String code);

    List<Campaign> findAllByIdIn(Collection<UUID> ids);

    List<Campaign> findByStatus(CampaignStatus status);

    boolean existsBySegmentIdAndStatusIn(UUID segmentId, Collection<CampaignStatus> statuses);

    /** @param creditorIds credores permitidos (nulo = sem restrição) */
    Page<Campaign> search(Collection<UUID> creditorIds, UUID creditorId, CampaignStatus status, Pageable pageable);
}
