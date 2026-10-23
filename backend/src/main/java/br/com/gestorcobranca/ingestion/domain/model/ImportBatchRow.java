package br.com.gestorcobranca.ingestion.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Registro de uma linha do lote pelo identificador externo do título (append-only). Detecta
 * título repetido no arquivo e, quando a linha foi gravada, guarda o resultado — gravado na
 * mesma transação da linha no módulo debt, o que torna a retomada exata: a linha reconhecida não
 * é gravada nem contada de novo.
 */
@Entity
@Table(name = "import_batch_row", schema = "ingestion")
public class ImportBatchRow extends TenantScopedEntity {

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "external_id", nullable = false, length = 80, updatable = false)
    private String externalId;

    @Column(name = "row_number", nullable = false, updatable = false)
    private long rowNumber;

    @Column(name = "title_id", updatable = false)
    private UUID titleId;

    @Column(name = "debtor_id", updatable = false)
    private UUID debtorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "title_outcome", length = 10, updatable = false)
    private RowOutcome titleOutcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "debtor_outcome", length = 10, updatable = false)
    private RowOutcome debtorOutcome;

    @Column(name = "warning_count", nullable = false, updatable = false)
    private int warningCount;

    protected ImportBatchRow() {
    }

    private ImportBatchRow(UUID batchId, String externalId, long rowNumber) {
        Guard.require(batchId != null && externalId != null && !externalId.isBlank() && rowNumber > 0,
                "import_batch_row.invalid", "Linha do lote inválida");
        this.batchId = batchId;
        this.externalId = externalId;
        this.rowNumber = rowNumber;
    }

    /** Linha registrada sem gravação (com erro, ou na pré-visualização). */
    public static ImportBatchRow registered(UUID batchId, String externalId, long rowNumber) {
        return new ImportBatchRow(batchId, externalId, rowNumber);
    }

    /** Linha gravada no módulo debt, com o resultado e o total de avisos gerados por ela. */
    public static ImportBatchRow written(UUID batchId, String externalId, long rowNumber, UUID titleId,
                                         RowOutcome titleOutcome, UUID debtorId, RowOutcome debtorOutcome,
                                         int warningCount) {
        ImportBatchRow row = new ImportBatchRow(batchId, externalId, rowNumber);
        Guard.require(titleId != null && debtorId != null && titleOutcome != null && debtorOutcome != null
                && warningCount >= 0, "import_batch_row.outcome_required", "Resultado da gravação obrigatório");
        row.titleId = titleId;
        row.debtorId = debtorId;
        row.titleOutcome = titleOutcome;
        row.debtorOutcome = debtorOutcome;
        row.warningCount = warningCount;
        return row;
    }

    public boolean isWritten() {
        return titleOutcome != null;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public String getExternalId() {
        return externalId;
    }

    public long getRowNumber() {
        return rowNumber;
    }

    public UUID getTitleId() {
        return titleId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public RowOutcome getTitleOutcome() {
        return titleOutcome;
    }

    public RowOutcome getDebtorOutcome() {
        return debtorOutcome;
    }

    public int getWarningCount() {
        return warningCount;
    }
}
