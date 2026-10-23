package br.com.gestorcobranca.campaign.infrastructure.persistence;

import br.com.gestorcobranca.campaign.domain.model.Experiment;
import br.com.gestorcobranca.campaign.domain.model.ExperimentAssignment;
import br.com.gestorcobranca.campaign.domain.model.ExperimentStatus;
import br.com.gestorcobranca.campaign.domain.repository.ExperimentRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ExperimentJpaRepository extends JpaRepository<Experiment, UUID> {

    Optional<Experiment> findFirstByCampaignIdAndStatus(UUID campaignId, ExperimentStatus status);

    List<Experiment> findByCampaignIdOrderByCreatedAtDesc(UUID campaignId);

    boolean existsByCampaignIdAndStatusIn(UUID campaignId, List<ExperimentStatus> statuses);

    List<Experiment> findByStatus(ExperimentStatus status);
}

interface ExperimentAssignmentJpaRepository extends JpaRepository<ExperimentAssignment, UUID> {

    Optional<ExperimentAssignment> findByExperimentIdAndDebtorId(UUID experimentId, UUID debtorId);
}

/** Agrega experimentos e atribuições de braço num único repositório de domínio. */
@Repository
class JpaExperimentRepository implements ExperimentRepository {

    private final ExperimentJpaRepository experiments;
    private final ExperimentAssignmentJpaRepository assignments;

    JpaExperimentRepository(ExperimentJpaRepository experiments, ExperimentAssignmentJpaRepository assignments) {
        this.experiments = experiments;
        this.assignments = assignments;
    }

    @Override
    public Experiment save(Experiment experiment) {
        return experiments.save(experiment);
    }

    @Override
    public Optional<Experiment> findById(UUID id) {
        return experiments.findById(id);
    }

    @Override
    public Optional<Experiment> findRunningByCampaign(UUID campaignId) {
        return experiments.findFirstByCampaignIdAndStatus(campaignId, ExperimentStatus.RUNNING);
    }

    @Override
    public List<Experiment> findByCampaignId(UUID campaignId) {
        return experiments.findByCampaignIdOrderByCreatedAtDesc(campaignId);
    }

    @Override
    public boolean existsOpenByCampaign(UUID campaignId) {
        return experiments.existsByCampaignIdAndStatusIn(campaignId, List.of(ExperimentStatus.DRAFT, ExperimentStatus.RUNNING));
    }

    @Override
    public List<Experiment> findByStatus(ExperimentStatus status) {
        return experiments.findByStatus(status);
    }

    @Override
    public ExperimentAssignment saveAssignment(ExperimentAssignment assignment) {
        return assignments.saveAndFlush(assignment);
    }

    @Override
    public Optional<ExperimentAssignment> findAssignment(UUID experimentId, UUID debtorId) {
        return assignments.findByExperimentIdAndDebtorId(experimentId, debtorId);
    }
}
