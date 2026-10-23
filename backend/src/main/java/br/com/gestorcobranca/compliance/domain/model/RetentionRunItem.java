package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** Devedor avaliado numa execução de retenção (append-only). */
@Entity
@Immutable
@Table(name = "retention_run_item", schema = "compliance")
public class RetentionRunItem extends TenantScopedEntity {

    public enum Outcome {
        /** Simulação: seria anonimizado. */
        ELIGIBLE,
        ANONYMIZED,
        /** Mantido: a base legal de um credor do devedor exige prazo maior que o da política. */
        RETAINED,
        FAILED
    }

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20, updatable = false)
    private Outcome outcome;

    @Column(name = "error_code", length = 100, updatable = false)
    private String errorCode;

    @Column(name = "detail", length = 500, updatable = false)
    private String detail;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected RetentionRunItem() {
    }

    public RetentionRunItem(UUID runId, UUID debtorId, Outcome outcome, String errorCode, String detail,
                            Instant processedAt) {
        Guard.require(runId != null && debtorId != null && outcome != null && processedAt != null,
                "retention.item_invalid", "Item de retenção inválido");
        Guard.require((outcome == Outcome.FAILED) == (errorCode != null), "retention.item_invalid",
                "Código de erro obrigatório somente em falhas");
        this.runId = runId;
        this.debtorId = debtorId;
        this.outcome = outcome;
        this.errorCode = errorCode == null || errorCode.length() <= 100 ? errorCode : errorCode.substring(0, 100);
        this.detail = detail == null || detail.length() <= 500 ? detail : detail.substring(0, 500);
        this.processedAt = processedAt;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
