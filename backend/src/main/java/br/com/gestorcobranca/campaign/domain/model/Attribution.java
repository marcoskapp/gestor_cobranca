package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Resultado atribuído a uma inscrição (append-only, único por inscrição × tipo × referência):
 * resposta, contato efetivo, acordo formalizado, pagamento confirmado e estorno (valor negativo).
 * Atribuição por "intenção de tratar": vale para inscrições tratadas e de controle cujo período de
 * observação contém o fato ({@link Enrollment#observes}), tenha a etapa sido executada ou não.
 */
@Entity
@Table(name = "attribution", schema = "campaign")
public class Attribution extends TenantScopedEntity {

    @Column(name = "enrollment_id", nullable = false, updatable = false)
    private UUID enrollmentId;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30, updatable = false)
    private AttributionKind kind;

    @Column(name = "reference_type", nullable = false, length = 40, updatable = false)
    private String referenceType;

    @Column(name = "reference_id", nullable = false, updatable = false)
    private UUID referenceId;

    @Column(name = "amount", updatable = false)
    private Money amount;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "experiment_id", updatable = false)
    private UUID experimentId;

    @Column(name = "variant_code", length = 40, updatable = false)
    private String variantCode;

    @Column(name = "control", nullable = false, updatable = false)
    private boolean control;

    protected Attribution() {
    }

    public Attribution(Enrollment enrollment, AttributionKind kind, String referenceType, UUID referenceId, Money amount,
                       Instant occurredAt) {
        this.enrollmentId = enrollment.getId();
        this.campaignId = enrollment.getCampaignId();
        this.debtorId = enrollment.getDebtorId();
        this.experimentId = enrollment.getExperimentId();
        this.variantCode = enrollment.getVariantCode();
        this.control = enrollment.isControl();
        this.kind = kind;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.amount = amount;
        this.occurredAt = occurredAt;
    }

    public UUID getEnrollmentId() {
        return enrollmentId;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public AttributionKind getKind() {
        return kind;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public Money getAmount() {
        return amount;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getExperimentId() {
        return experimentId;
    }

    public String getVariantCode() {
        return variantCode;
    }

    public boolean isControl() {
        return control;
    }
}
