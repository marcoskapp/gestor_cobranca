package br.com.gestorcobranca.publicapi.domain.model.platform;

import br.com.gestorcobranca.publicapi.domain.model.BillableUsage;
import br.com.gestorcobranca.publicapi.domain.model.Proration;
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
 * Extrato da plataforma para a assessoria num ciclo da política de cobrança da plataforma: consumo agregado de todos
 * os tokens da assessoria (inclusive revogados ou expirados durante o ciclo), de todos os credores, com a versão da
 * política aplicada, as linhas calculadas e o detalhamento por credor, por token e por operação (JSON). Gravado uma
 * vez por ciclo, imutável (append-only no banco); um novo fechamento do mesmo ciclo gera uma nova revisão com motivo.
 * O {@code usage_hash} (SHA-256 do consumo usado) prova que um recálculo sobre o mesmo consumo dá o mesmo valor.
 */
@Entity
@Table(name = "platform_statement", schema = "publicapi")
public class PlatformStatement extends TenantScopedEntity {

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDate periodEnd;

    @Column(name = "revision", nullable = false, updatable = false)
    private int revision;

    @Column(name = "closing_day", nullable = false, updatable = false)
    private int closingDay;

    @Column(name = "cycle_days", nullable = false, updatable = false)
    private int cycleDays;

    @Column(name = "reference_cycle_days", nullable = false, updatable = false)
    private int referenceCycleDays;

    @Column(name = "cycle_kind", nullable = false, updatable = false, length = 20)
    private String cycleKind;

    @Column(name = "policy_id", nullable = false, updatable = false)
    private UUID policyId;

    @Column(name = "policy_version_id", nullable = false, updatable = false)
    private UUID policyVersionId;

    @Column(name = "policy_version", nullable = false, updatable = false)
    private int policyVersion;

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

    @Column(name = "creditors", nullable = false, updatable = false)
    private int creditors;

    @Column(name = "tokens", nullable = false, updatable = false)
    private int tokens;

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

    @Column(name = "usage_hash", nullable = false, updatable = false, length = 64)
    private String usageHash;

    @Column(name = "closed_at", nullable = false, updatable = false)
    private Instant closedAt;

    /** Usuário da plataforma que fechou ou revisou; {@code null} no fechamento automático. */
    @Column(name = "closed_by", updatable = false)
    private UUID closedBy;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    protected PlatformStatement() {
    }

    /** Política aplicada ao ciclo. */
    public record AppliedPolicy(UUID policyId, UUID versionId, int version) {
    }

    /** Quantidades do ciclo: requisições medidas, faturáveis e quantos credores e tokens consumiram. */
    public record Quantities(long requests, BillableUsage billable, int creditors, int tokens) {
    }

    public PlatformStatement(LocalDate periodStart, LocalDate periodEnd, int revision, int closingDay,
                             Proration proration, String cycleKind, AppliedPolicy policy, Quantities quantities,
                             BigDecimal totalAmount, String linesJson, String breakdownJson, String usageHash,
                             Instant closedAt, UUID closedBy, String reason) {
        Guard.require(periodStart != null && periodEnd != null && !periodEnd.isBefore(periodStart),
                "platform_statement.period_invalid", "Período do ciclo inválido");
        Guard.require(revision >= 1, "platform_statement.revision_invalid", "Revisão inválida");
        Guard.require(revision == 1 || (reason != null && !reason.isBlank()), "platform_statement.reason_required",
                "Informe o motivo do novo fechamento do ciclo");
        Guard.require(policy != null && quantities != null && totalAmount != null && totalAmount.signum() >= 0,
                "platform_statement.values_invalid", "Extrato inválido");
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.revision = revision;
        this.closingDay = closingDay;
        this.cycleDays = proration.cycleDays();
        this.referenceCycleDays = proration.referenceDays();
        this.cycleKind = cycleKind;
        this.policyId = policy.policyId();
        this.policyVersionId = policy.versionId();
        this.policyVersion = policy.version();
        this.requests = quantities.requests();
        this.billableRequests = quantities.billable().requests();
        this.billableMbIn = quantities.billable().mbIn();
        this.billableMbOut = quantities.billable().mbOut();
        this.records = quantities.billable().records();
        this.creditors = quantities.creditors();
        this.tokens = quantities.tokens();
        this.totalAmount = totalAmount;
        this.currency = "BRL";
        this.lines = linesJson;
        this.breakdown = breakdownJson;
        this.usageHash = usageHash;
        this.closedAt = closedAt;
        this.closedBy = closedBy;
        this.reason = reason == null || reason.isBlank() ? null : reason.strip();
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

    public int getClosingDay() {
        return closingDay;
    }

    public int getCycleDays() {
        return cycleDays;
    }

    public int getReferenceCycleDays() {
        return referenceCycleDays;
    }

    public String getCycleKind() {
        return cycleKind;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public UUID getPolicyVersionId() {
        return policyVersionId;
    }

    public int getPolicyVersion() {
        return policyVersion;
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

    public int getCreditors() {
        return creditors;
    }

    public int getTokens() {
        return tokens;
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
