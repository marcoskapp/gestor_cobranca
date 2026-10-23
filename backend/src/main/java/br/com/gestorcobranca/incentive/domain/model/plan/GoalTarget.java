package br.com.gestorcobranca.incentive.domain.model.plan;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Meta de um negociador ou equipe num período; prevalece sobre a meta padrão do plano. */
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

    protected GoalTarget() {
    }

    public GoalTarget(LocalDate periodStart, SubjectType subjectType, UUID subjectId, BigDecimal targetValue) {
        Guard.require(periodStart != null && periodStart.getDayOfMonth() == 1, "goal.period_invalid",
                "Período deve começar no dia 1");
        Guard.require(subjectType != null && subjectId != null, "goal.subject_required", "Informe o negociador ou a equipe");
        this.periodStart = periodStart;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        changeTarget(targetValue);
    }

    public void changeTarget(BigDecimal value) {
        Guard.require(value != null && value.signum() > 0, "goal.target_invalid", "Meta deve ser positiva");
        this.targetValue = value;
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
}
