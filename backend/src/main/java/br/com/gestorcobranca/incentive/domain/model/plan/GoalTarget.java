package br.com.gestorcobranca.incentive.domain.model.plan;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Meta de um negociador ou equipe num período; prevalece sobre a meta padrão do plano quando está
 * na unidade da métrica do plano ({@link GoalMetric#unit()}).
 *
 * <p>Metas gravadas antes da unidade explícita (V1806) sem unidade dedutível ficam com unidade
 * nula: seguem a métrica do plano, como antes. Toda gravação nova exige a unidade.
 */
@Entity
@Table(name = "goal_target", schema = "incentive")
public class GoalTarget extends TenantScopedEntity {

    public enum SubjectType { USER, TEAM }

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "subject_type", nullable = false, updatable = false, length = 10)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "target_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal targetValue;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "unit", length = 10)
    private GoalUnit unit;

    private static final BigDecimal MAX_PERCENT = new BigDecimal("100");
    /** numeric(19, 4): 15 algarismos inteiros. */
    private static final BigDecimal MAX_CURRENCY = new BigDecimal("1000000000000000");

    protected GoalTarget() {
    }

    public GoalTarget(LocalDate periodStart, SubjectType subjectType, UUID subjectId, BigDecimal targetValue,
                      GoalUnit unit) {
        Guard.require(periodStart != null && periodStart.getDayOfMonth() == 1, "goal.period_invalid",
                "Período deve começar no dia 1");
        Guard.require(subjectType != null && subjectId != null, "goal.subject_required", "Informe o negociador ou a equipe");
        this.periodStart = periodStart;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        define(targetValue, unit);
    }

    /**
     * Define valor e unidade juntos (a unidade nunca muda sem o valor ser informado de novo). R$ com
     * até 2 casas; % de 0,01 a 100 com até 2 casas (a taxa de recuperação é fração do saldo
     * elegível, não passa de 100%).
     */
    public void define(BigDecimal value, GoalUnit newUnit) {
        Guard.require(newUnit != null, "goal.unit_required", "Informe se a meta é em valor (R$) ou percentual (%)");
        Guard.require(value != null && value.signum() > 0, "goal.target_invalid", "Meta deve ser positiva");
        Guard.require(value.stripTrailingZeros().scale() <= 2, "goal.target_scale_invalid",
                newUnit == GoalUnit.CURRENCY ? "Meta em R$ aceita no máximo 2 casas decimais (centavos)"
                        : "Meta em % aceita no máximo 2 casas decimais");
        Guard.require(newUnit != GoalUnit.PERCENT || value.compareTo(MAX_PERCENT) <= 0, "goal.percent_out_of_range",
                "Meta percentual deve ficar entre 0,01% e 100%");
        Guard.require(newUnit != GoalUnit.CURRENCY || value.compareTo(MAX_CURRENCY) < 0, "goal.target_too_large",
                "Meta acima do limite aceito");
        this.targetValue = value;
        this.unit = newUnit;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public SubjectType getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public BigDecimal getTargetValue() {
        return targetValue;
    }

    /** Unidade da meta; nula só em meta antiga sem unidade dedutível (segue a métrica do plano). */
    public GoalUnit getUnit() {
        return unit;
    }
}
