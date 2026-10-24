package br.com.gestorcobranca.ingestion.domain.model.review;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Uma conferência de totais (append-only): o que o usuário informou, o que o arquivo convertido soma, o resultado e as
 * dicas apresentadas para localizar a diferença. Quem conferiu é {@code createdBy}.
 */
@Entity
@Table(name = "import_totals_check", schema = "ingestion")
public class ImportTotalsCheck extends TenantScopedEntity {

    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "entered_rows", updatable = false)
    private Long enteredRows;

    @Column(name = "entered_principal", precision = 19, scale = 2, updatable = false)
    private BigDecimal enteredPrincipal;

    @Column(name = "entered_balance", precision = 19, scale = 2, updatable = false)
    private BigDecimal enteredBalance;

    @Column(name = "entered_contract", precision = 19, scale = 2, updatable = false)
    private BigDecimal enteredContract;

    @Column(name = "computed_rows", nullable = false, updatable = false)
    private long computedRows;

    @Column(name = "computed_principal", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal computedPrincipal;

    @Column(name = "computed_balance", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal computedBalance;

    @Column(name = "computed_contract", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal computedContract;

    @Column(name = "matched", nullable = false, updatable = false)
    private boolean matched;

    @Column(name = "scale_exponent", updatable = false)
    private Integer scaleExponent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hints", updatable = false)
    private String hints;

    protected ImportTotalsCheck() {
    }

    public ImportTotalsCheck(UUID reviewId, UUID batchId, ControlTotalsInput entered, ComputedTotals computed,
                             TotalsComparison comparison, String hintsJson) {
        this.reviewId = reviewId;
        this.batchId = batchId;
        this.enteredRows = entered.rows();
        this.enteredPrincipal = entered.principal();
        this.enteredBalance = entered.balance();
        this.enteredContract = entered.contractAmount();
        this.computedRows = computed.rows();
        this.computedPrincipal = computed.value(TotalsMeasure.PRINCIPAL);
        this.computedBalance = computed.value(TotalsMeasure.BALANCE);
        this.computedContract = computed.value(TotalsMeasure.CONTRACT_AMOUNT);
        this.matched = comparison.matches();
        this.scaleExponent = comparison.scaleHint().map(MeasureComparison::scaleExponent).orElse(null);
        this.hints = hintsJson;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public Long getEnteredRows() {
        return enteredRows;
    }

    public BigDecimal getEnteredPrincipal() {
        return enteredPrincipal;
    }

    public BigDecimal getEnteredBalance() {
        return enteredBalance;
    }

    public BigDecimal getEnteredContract() {
        return enteredContract;
    }

    public long getComputedRows() {
        return computedRows;
    }

    public BigDecimal getComputedPrincipal() {
        return computedPrincipal;
    }

    public BigDecimal getComputedBalance() {
        return computedBalance;
    }

    public BigDecimal getComputedContract() {
        return computedContract;
    }

    public boolean isMatched() {
        return matched;
    }

    public Integer getScaleExponent() {
        return scaleExponent;
    }

    public String getHints() {
        return hints;
    }
}
