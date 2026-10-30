package br.com.gestorcobranca.publicapi.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Extrato mensal de consumo da API pública de um credor: totais do mês, versão da tabela de preços aplicada, linhas
 * calculadas e detalhamento por token e por rota (JSON). Gravado uma vez por credor e mês, imutável (append-only no
 * banco); um novo fechamento do mesmo mês não substitui o anterior, gera uma nova revisão (reabertura auditada) — e o
 * {@code usage_hash} permite verificar que o extrato foi calculado sobre exatamente o mesmo consumo.
 */
@Entity
@Table(name = "usage_statement", schema = "publicapi")
public class UsageStatement extends TenantScopedEntity {

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(name = "revision", nullable = false, updatable = false)
    private int revision;

    @Column(name = "price_plan_id", updatable = false)
    private UUID pricePlanId;

    @Column(name = "price_plan_code", updatable = false, length = 40)
    private String pricePlanCode;

    @Column(name = "price_plan_version", updatable = false)
    private Integer pricePlanVersion;

    @Column(name = "requests", nullable = false, updatable = false)
    private long requests;

    @Column(name = "billable_requests", nullable = false, updatable = false)
    private long billableRequests;

    @Column(name = "bytes_in", nullable = false, updatable = false)
    private long bytesIn;

    @Column(name = "bytes_out", nullable = false, updatable = false)
    private long bytesOut;

    @Column(name = "records", nullable = false, updatable = false)
    private long records;

    @Column(name = "total_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lines", nullable = false, updatable = false)
    private String lines;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "breakdown", nullable = false, updatable = false)
    private String breakdown;

    /** SHA-256 do consumo agregado usado no cálculo (prova de reprodutibilidade). */
    @Column(name = "usage_hash", nullable = false, updatable = false, length = 64)
    private String usageHash;

    @Column(name = "closed_at", nullable = false, updatable = false)
    private Instant closedAt;

    @Column(name = "closed_by", updatable = false)
    private UUID closedBy;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    protected UsageStatement() {
    }

    public UsageStatement(UUID creditorId, LocalDate periodStart, int revision, PricePlan plan, UsageTotals totals,
                          BigDecimal totalAmount, String linesJson, String breakdownJson, String usageHash,
                          Instant closedAt, UUID closedBy, String reason) {
        Guard.require(periodStart.getDayOfMonth() == 1, "usage_statement.period_invalid", "Período mensal inválido");
        Guard.require(revision >= 1, "usage_statement.revision_invalid", "Revisão inválida");
        Guard.require(revision == 1 || (reason != null && !reason.isBlank()), "usage_statement.reason_required",
                "Informe o motivo do novo fechamento do mês");
        this.creditorId = creditorId;
        this.periodStart = periodStart;
        this.revision = revision;
        if (plan != null) {
            this.pricePlanId = plan.getId();
            this.pricePlanCode = plan.getCode();
            this.pricePlanVersion = plan.getPlanVersion();
        }
        this.requests = totals.requests();
        this.billableRequests = totals.billableRequests();
        this.bytesIn = totals.bytesIn();
        this.bytesOut = totals.bytesOut();
        this.records = totals.records();
        this.totalAmount = totalAmount;
        this.currency = "BRL";
        this.lines = linesJson;
        this.breakdown = breakdownJson;
        this.usageHash = usageHash;
        this.closedAt = closedAt;
        this.closedBy = closedBy;
        this.reason = reason == null || reason.isBlank() ? null : reason.strip();
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public int getRevision() {
        return revision;
    }

    public UUID getPricePlanId() {
        return pricePlanId;
    }

    public String getPricePlanCode() {
        return pricePlanCode;
    }

    public Integer getPricePlanVersion() {
        return pricePlanVersion;
    }

    public long getRequests() {
        return requests;
    }

    public long getBillableRequests() {
        return billableRequests;
    }

    public long getBytesIn() {
        return bytesIn;
    }

    public long getBytesOut() {
        return bytesOut;
    }

    public long getRecords() {
        return records;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getLines() {
        return lines;
    }

    public String getBreakdown() {
        return breakdown;
    }

    public String getUsageHash() {
        return usageHash;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public UUID getClosedBy() {
        return closedBy;
    }

    public String getReason() {
        return reason;
    }
}
