package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Avaliação de uma garantia (append-only: o banco recusa UPDATE/DELETE). Uma avaliação
 * incorreta é superada por outra, nunca alterada. Avaliações vindas de carga referenciam o
 * lote, único por garantia (reimportação não duplica).
 */
@Entity
@Immutable
@Table(name = "collateral_appraisal", schema = "debt")
public class CollateralAppraisal extends TenantScopedEntity {

    @Column(name = "collateral_id", nullable = false, updatable = false)
    private UUID collateralId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money amount;

    @Column(name = "appraisal_date", nullable = false, updatable = false)
    private LocalDate appraisalDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, updatable = false)
    private AppraisalSource source;

    @Column(name = "notes", length = 500, updatable = false)
    private String notes;

    @Column(name = "import_batch_id", updatable = false)
    private UUID importBatchId;

    protected CollateralAppraisal() {
    }

    /** @param importBatchId lote de importação de origem (nulo em cadastro manual) */
    public CollateralAppraisal(UUID collateralId, Money amount, LocalDate appraisalDate, AppraisalSource source,
                               String notes, UUID importBatchId) {
        this.collateralId = Objects.requireNonNull(collateralId, "collateralId");
        Guard.require(amount != null && !amount.isNegative(), "collateral.appraisal_amount_invalid",
                "Valor da avaliação não pode ser negativo");
        Guard.require(appraisalDate != null, "collateral.appraisal_date_required", "Data da avaliação obrigatória");
        Guard.require(source != null, "collateral.appraisal_source_required", "Fonte da avaliação obrigatória");
        Guard.require(notes == null || notes.length() <= 500, "collateral.appraisal_notes_too_long", "Observação muito longa");
        this.amount = amount;
        this.appraisalDate = appraisalDate;
        this.source = source;
        this.notes = notes == null || notes.isBlank() ? null : notes.trim();
        this.importBatchId = importBatchId;
    }

    /** Mesma posição já registrada (reimportação de carga com a mesma avaliação). */
    public boolean samePositionAs(Money amount, LocalDate date, AppraisalSource source) {
        return this.amount.equals(amount) && this.appraisalDate.equals(date) && this.source == source;
    }

    public UUID getCollateralId() {
        return collateralId;
    }

    public Money getAmount() {
        return amount;
    }

    public LocalDate getAppraisalDate() {
        return appraisalDate;
    }

    public AppraisalSource getSource() {
        return source;
    }

    public String getNotes() {
        return notes;
    }

    public UUID getImportBatchId() {
        return importBatchId;
    }
}
