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
 * Extrato de um ciclo de consumo da API pública de um credor: período (datas inclusivas no fuso de negócio), versão
 * das condições de cobrança do contrato aplicada, quantidades faturáveis inteiras (requisições cobráveis, MB recebidos,
 * MB enviados, registros), linhas calculadas e detalhamento por token e por rota (JSON). Gravado uma vez por credor e
 * ciclo, imutável (append-only no banco); um novo fechamento do mesmo ciclo não substitui o anterior, gera uma nova
 * revisão com motivo — e o {@code usage_hash} permite verificar que o extrato foi calculado sobre exatamente o mesmo
 * consumo.
 */
@Entity
@Table(name = "usage_statement", schema = "publicapi")
public class UsageStatement extends TenantScopedEntity {

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDate periodEnd;

    @Column(name = "revision", nullable = false, updatable = false)
    private int revision;

    @Column(name = "usage_closing_day", nullable = false, updatable = false)
    private int usageClosingDay;

    @Column(name = "contract_id", updatable = false)
    private UUID contractId;

    @Column(name = "contract_number", updatable = false, length = 60)
    private String contractNumber;

    @Column(name = "pricing_id", updatable = false)
    private UUID pricingId;

    @Column(name = "pricing_version", updatable = false)
    private Integer pricingVersion;

    @Column(name = "requests", nullable = false, updatable = false)
    private long requests;

    @Column(name = "billable_requests", nullable = false, updatable = false)
    private long billableRequests;

    @Column(name = "billable_mb_in", nullable = false, updatable = false)
    private long billableMbIn;

    @Column(name = "billable_mb_out", nullable = false, updatable = false)
    private long billableMbOut;

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

    /** Versão das condições do contrato aplicada ao ciclo (ausente quando o contrato não tem condições). */
    public record AppliedPricing(UUID contractId, String contractNumber, UUID pricingId, int pricingVersion) {
    }

    public UsageStatement(UUID creditorId, LocalDate periodStart, LocalDate periodEnd, int revision, int usageClosingDay,
                          AppliedPricing pricing, long requests, BillableUsage billable, BigDecimal totalAmount,
                          String linesJson, String breakdownJson, String usageHash, Instant closedAt, UUID closedBy,
                          String reason) {
        Guard.require(periodStart != null && periodEnd != null && !periodEnd.isBefore(periodStart),
                "usage_statement.period_invalid", "Período do ciclo inválido");
        Guard.require(revision >= 1, "usage_statement.revision_invalid", "Revisão inválida");
        Guard.require(revision == 1 || (reason != null && !reason.isBlank()), "usage_statement.reason_required",
                "Informe o motivo do novo fechamento do ciclo");
        this.creditorId = creditorId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.revision = revision;
        this.usageClosingDay = usageClosingDay;
        if (pricing != null) {
            this.contractId = pricing.contractId();
            this.contractNumber = pricing.contractNumber();
            this.pricingId = pricing.pricingId();
            this.pricingVersion = pricing.pricingVersion();
        }
        this.requests = requests;
        this.billableRequests = billable.requests();
        this.billableMbIn = billable.mbIn();
        this.billableMbOut = billable.mbOut();
        this.records = billable.records();
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

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public int getRevision() {
        return revision;
    }

    public int getUsageClosingDay() {
        return usageClosingDay;
    }

    public UUID getContractId() {
        return contractId;
    }

    public String getContractNumber() {
        return contractNumber;
    }

    public UUID getPricingId() {
        return pricingId;
    }

    public Integer getPricingVersion() {
        return pricingVersion;
    }

    public long getRequests() {
        return requests;
    }

    public long getBillableRequests() {
        return billableRequests;
    }

    public long getBillableMbIn() {
        return billableMbIn;
    }

    public long getBillableMbOut() {
        return billableMbOut;
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
