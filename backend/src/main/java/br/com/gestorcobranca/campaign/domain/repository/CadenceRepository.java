package br.com.gestorcobranca.campaign.domain.repository;

import br.com.gestorcobranca.campaign.domain.model.Cadence;
import br.com.gestorcobranca.campaign.domain.model.CadenceStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CadenceRepository {

    Cadence save(Cadence cadence);

    Cadence saveAndFlush(Cadence cadence);

    Optional<Cadence> findById(UUID id);

    Optional<Cadence> findByCampaignIdAndStatus(UUID campaignId, CadenceStatus status);

    List<Cadence> findByCampaignIdOrderByVersionNumberDesc(UUID campaignId);

    int maxVersion(UUID campaignId);
}
