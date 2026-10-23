package br.com.gestorcobranca.campaign.domain.repository;

import br.com.gestorcobranca.campaign.domain.model.Experiment;
import br.com.gestorcobranca.campaign.domain.model.ExperimentAssignment;
import br.com.gestorcobranca.campaign.domain.model.ExperimentStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExperimentRepository {

    Experiment save(Experiment experiment);

    Optional<Experiment> findById(UUID id);

    Optional<Experiment> findRunningByCampaign(UUID campaignId);

    List<Experiment> findByCampaignId(UUID campaignId);

    boolean existsOpenByCampaign(UUID campaignId);

    List<Experiment> findByStatus(ExperimentStatus status);

    ExperimentAssignment saveAssignment(ExperimentAssignment assignment);

    Optional<ExperimentAssignment> findAssignment(UUID experimentId, UUID debtorId);
}
