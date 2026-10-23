package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Braço do teste A/B atribuído a um devedor (append-only, único por experimento e devedor): todos os
 * casos do devedor na campanha ficam no mesmo braço.
 *
 * @see br.com.gestorcobranca.campaign.domain.service.VariantAllocator
 */
@Entity
@Table(name = "experiment_assignment", schema = "campaign")
public class ExperimentAssignment extends TenantScopedEntity {

    @Column(name = "experiment_id", nullable = false, updatable = false)
    private UUID experimentId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "arm", nullable = false, length = 40, updatable = false)
    private String arm;

    @Column(name = "control", nullable = false, updatable = false)
    private boolean control;

    @Column(name = "bucket", nullable = false, updatable = false)
    private int bucket;

    protected ExperimentAssignment() {
    }

    public ExperimentAssignment(UUID experimentId, UUID debtorId, String arm, boolean control, int bucket) {
        this.experimentId = experimentId;
        this.debtorId = debtorId;
        this.arm = arm;
        this.control = control;
        this.bucket = bucket;
    }

    public UUID getExperimentId() {
        return experimentId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public String getArm() {
        return arm;
    }

    public boolean isControl() {
        return control;
    }

    public int getBucket() {
        return bucket;
    }
}
