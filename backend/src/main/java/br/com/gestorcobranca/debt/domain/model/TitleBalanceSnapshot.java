package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Saldo do título informado pelo credor numa carga (append-only). Não altera o razão: serve
 * para conciliar a posição do credor com o saldo interno e detectar divergências (pagamentos
 * feitos diretamente ao credor, correções não comunicadas).
 */
@Entity
@Immutable
@Table(name = "title_balance_snapshot", schema = "debt")
public class TitleBalanceSnapshot extends TenantScopedEntity {

    @Column(name = "title_id", nullable = false, updatable = false)
    private UUID titleId;

    @Column(name = "reference_date", nullable = false, updatable = false)
    private LocalDate referenceDate;

    @Column(name = "reported_balance", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money reportedBalance;

    @Column(name = "import_batch_id", nullable = false, updatable = false)
    private UUID importBatchId;

    protected TitleBalanceSnapshot() {
    }

    public TitleBalanceSnapshot(UUID titleId, LocalDate referenceDate, Money reportedBalance, UUID importBatchId) {
        this.titleId = Objects.requireNonNull(titleId, "titleId");
        Guard.require(referenceDate != null, "title.balance_reference_date_required", "Data de referência do saldo obrigatória");
        Guard.require(reportedBalance != null && !reportedBalance.isNegative(), "title.reported_balance_invalid",
                "Saldo informado não pode ser negativo");
        Guard.require(importBatchId != null, "title.import_batch_required", "Lote de importação obrigatório");
        this.referenceDate = referenceDate;
        this.reportedBalance = reportedBalance;
        this.importBatchId = importBatchId;
    }

    /** Mesma posição já registrada (reimportação do mesmo arquivo não gera novo registro). */
    public boolean samePositionAs(LocalDate referenceDate, Money reportedBalance) {
        return this.referenceDate.equals(referenceDate) && this.reportedBalance.equals(reportedBalance);
    }

    public UUID getTitleId() {
        return titleId;
    }

    public LocalDate getReferenceDate() {
        return referenceDate;
    }

    public Money getReportedBalance() {
        return reportedBalance;
    }

    public UUID getImportBatchId() {
        return importBatchId;
    }
}
