package br.com.gestorcobranca.incentive.domain.model.statement;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Extrato de comissão de um negociador num período (§5): resultado do motor sobre os lançamentos
 * liberados, com memória de cálculo reprodutível (versão do plano, entradas e linhas), ajustes e
 * aprovação segregada. Aprovado é imutável (também no banco); correções entram no período
 * seguinte como ajuste.
 */
@Entity
@Table(name = "commission_statement", schema = "incentive")
public class CommissionStatement extends TenantScopedEntity {

    @Column(name = "period_id", nullable = false, updatable = false)
    private UUID periodId;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(name = "beneficiary_id", nullable = false, updatable = false)
    private UUID beneficiaryId;

    @Column(name = "plan_id")
    private UUID planId;

    @Column(name = "plan_code", length = 40)
    private String planCode;

    @Column(name = "plan_version")
    private Integer planVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StatementStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 30)
    private StatementOutcome outcome;

    @Column(name = "calculated_amount", nullable = false, precision = 19, scale = 2)
    private Money calculatedAmount;

    @Column(name = "adjustments_amount", nullable = false, precision = 19, scale = 2)
    private Money adjustmentsAmount;

    @Column(name = "payable_amount", nullable = false, precision = 19, scale = 2)
    private Money payableAmount;

    @Column(name = "withheld_amount", nullable = false, precision = 19, scale = 2)
    private Money withheldAmount;

    @Column(name = "credited_recovery", nullable = false, precision = 19, scale = 2)
    private Money creditedRecovery;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calculation", nullable = false)
    private String calculation;

    @Column(name = "calculation_schema", nullable = false)
    private int calculationSchema;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    @Column(name = "calculated_by")
    private UUID calculatedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "exported_at")
    private Instant exportedAt;

    @Column(name = "export_id")
    private UUID exportId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "commission_statement_line", schema = "incentive",
            joinColumns = @JoinColumn(name = "statement_id"))
    @OrderBy("lineNumber")
    private List<StatementLine> lines = new ArrayList<>();

    protected CommissionStatement() {
    }

    /** Referência à versão do plano usada (nula quando não há plano). */
    public record PlanRef(UUID planId, String code, Integer version) {
        public static final PlanRef NONE = new PlanRef(null, null, null);
    }

    /** Resultado do cálculo a gravar (cálculo inicial ou recálculo). */
    public record Calculation(PlanRef plan, StatementOutcome outcome, Money calculated, Money withheld,
                              Money creditedRecovery, List<StatementLine> lines, String memoryJson, int memorySchema) {
    }

    public CommissionStatement(UUID periodId, LocalDate periodStart, UUID beneficiaryId, Calculation calculation,
                               UUID actorId, Instant now) {
        this.periodId = Objects.requireNonNull(periodId, "periodId");
        this.periodStart = Objects.requireNonNull(periodStart, "periodStart");
        this.beneficiaryId = Objects.requireNonNull(beneficiaryId, "beneficiaryId");
        this.status = StatementStatus.CALCULATED;
        this.adjustmentsAmount = Money.ZERO;
        apply(calculation, actorId, now);
    }

    /** Recalcula (ex.: após nova monitoria ou ocorrência dispensada); preserva contestações e ajustes. */
    public void recalculate(Calculation calculation, UUID actorId, Instant now) {
        requireOpen();
        apply(calculation, actorId, now);
    }

    private void apply(Calculation c, UUID actorId, Instant now) {
        Guard.require(c.calculated() != null && !c.calculated().isNegative(), "statement.amount_invalid",
                "Valor calculado inválido");
        this.planId = c.plan().planId();
        this.planCode = c.plan().code();
        this.planVersion = c.plan().version();
        this.outcome = c.outcome();
        this.calculatedAmount = c.calculated();
        this.withheldAmount = c.withheld();
        this.creditedRecovery = c.creditedRecovery();
        this.calculation = c.memoryJson();
        this.calculationSchema = c.memorySchema();
        this.calculatedAt = now;
        this.calculatedBy = actorId;
        this.lines.clear();
        this.lines.addAll(c.lines());
        refreshPayable();
    }

    public void openDispute() {
        requireOpen();
        this.status = StatementStatus.DISPUTED;
    }

    /**
     * Após resolver uma contestação ou aplicar um ajuste.
     *
     * @param openDisputes      contestações ainda abertas
     * @param appliedAdjustments soma dos ajustes aplicados
     */
    public void reconcile(int openDisputes, Money appliedAdjustments, boolean anyAdjustment) {
        requireOpen();
        this.adjustmentsAmount = appliedAdjustments;
        refreshPayable();
        if (openDisputes > 0) {
            this.status = StatementStatus.DISPUTED;
        } else {
            this.status = anyAdjustment ? StatementStatus.ADJUSTED : StatementStatus.CALCULATED;
        }
    }

    /**
     * Aprovação financeira segregada: quem aprova não é o beneficiário, nem quem fechou o período,
     * nem quem requereu ou decidiu ajustes do extrato.
     */
    public void approve(UUID approverId, UUID periodClosedBy, Collection<UUID> adjustmentActors, int openDisputes,
                        int pendingAdjustments, Instant now) {
        Guard.requireState(status == StatementStatus.CALCULATED || status == StatementStatus.ADJUSTED,
                "statement.not_approvable", "Extrato não está pronto para aprovação");
        Guard.requireState(openDisputes == 0, "statement.open_disputes", "Há contestações abertas");
        Guard.requireState(pendingAdjustments == 0, "statement.pending_adjustments", "Há ajustes aguardando segunda aprovação");
        Guard.require(!approverId.equals(beneficiaryId), "statement.self_approval", "Não é permitido aprovar o próprio extrato");
        Guard.require(!approverId.equals(periodClosedBy), "statement.segregation_closer",
                "Quem fechou o período não pode aprovar o extrato (segregação de funções)");
        Guard.require(!adjustmentActors.contains(approverId), "statement.segregation_adjuster",
                "Quem ajustou o extrato não pode aprová-lo (segregação de funções)");
        this.status = StatementStatus.APPROVED;
        this.approvedAt = now;
        this.approvedBy = approverId;
    }

    public void markExported(UUID exportId, Instant now) {
        Guard.requireState(status == StatementStatus.APPROVED || status == StatementStatus.EXPORTED,
                "statement.not_approved", "Somente extrato aprovado pode ser exportado");
        if (status == StatementStatus.APPROVED) {
            this.status = StatementStatus.EXPORTED;
            this.exportedAt = now;
            this.exportId = exportId;
        }
    }

    private void refreshPayable() {
        Money total = calculatedAmount.plus(adjustmentsAmount == null ? Money.ZERO : adjustmentsAmount);
        this.payableAmount = total.isNegative() ? Money.ZERO : total;
    }

    private void requireOpen() {
        Guard.requireState(!status.isFinal(), "statement.final", "Extrato aprovado é imutável; ajuste no período seguinte");
    }

    public boolean isFinal() {
        return status.isFinal();
    }

    public UUID getPeriodId() {
        return periodId;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public UUID getBeneficiaryId() {
        return beneficiaryId;
    }

    public UUID getPlanId() {
        return planId;
    }

    public String getPlanCode() {
        return planCode;
    }

    public Integer getPlanVersion() {
        return planVersion;
    }

    public StatementStatus getStatus() {
        return status;
    }

    public StatementOutcome getOutcome() {
        return outcome;
    }

    public Money getCalculatedAmount() {
        return calculatedAmount;
    }

    public Money getAdjustmentsAmount() {
        return adjustmentsAmount;
    }

    public Money getPayableAmount() {
        return payableAmount;
    }

    public Money getWithheldAmount() {
        return withheldAmount;
    }

    public Money getCreditedRecovery() {
        return creditedRecovery;
    }

    public String getCalculation() {
        return calculation;
    }

    public int getCalculationSchema() {
        return calculationSchema;
    }

    public Instant getCalculatedAt() {
        return calculatedAt;
    }

    public UUID getCalculatedBy() {
        return calculatedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public Instant getExportedAt() {
        return exportedAt;
    }

    public UUID getExportId() {
        return exportId;
    }

    public List<StatementLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
