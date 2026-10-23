package br.com.gestorcobranca.incentive.domain.model.ledger;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Período mensal de apuração. Fechado uma única vez (gera os extratos); não reabre — correções
 * entram no período seguinte como ajuste.
 */
@Entity
@Table(name = "commission_period", schema = "incentive")
public class CommissionPeriod extends TenantScopedEntity {

    public enum CloseTrigger { SCHEDULED, MANUAL }

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PeriodStatus status;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_trigger", length = 20)
    private CloseTrigger closeTrigger;

    @Column(name = "statements_count")
    private Integer statementsCount;

    protected CommissionPeriod() {
    }

    public CommissionPeriod(YearMonth month) {
        this.periodStart = month.atDay(1);
        this.periodEnd = month.atEndOfMonth();
        this.status = PeriodStatus.OPEN;
    }

    public static LocalDate startOf(LocalDate date) {
        return date.withDayOfMonth(1);
    }

    public YearMonth month() {
        return YearMonth.from(periodStart);
    }

    public void close(UUID actorId, CloseTrigger trigger, int statements, Instant now) {
        Guard.requireState(status == PeriodStatus.OPEN, "commission_period.already_closed", "Período já fechado");
        this.status = PeriodStatus.CLOSED;
        this.closedAt = now;
        this.closedBy = actorId;
        this.closeTrigger = trigger;
        this.statementsCount = statements;
    }

    public boolean isClosed() {
        return status == PeriodStatus.CLOSED;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public PeriodStatus getStatus() {
        return status;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public UUID getClosedBy() {
        return closedBy;
    }

    public CloseTrigger getCloseTrigger() {
        return closeTrigger;
    }

    public Integer getStatementsCount() {
        return statementsCount;
    }
}
