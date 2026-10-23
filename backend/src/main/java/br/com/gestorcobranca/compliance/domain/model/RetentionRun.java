package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Execução da política de retenção (simulada ou efetiva), com os totais processados. Os itens
 * ({@link RetentionRunItem}) são a evidência do que foi (ou seria) descartado.
 */
@Entity
@Table(name = "retention_run", schema = "compliance")
public class RetentionRun extends TenantScopedEntity {

    public enum Trigger { SCHEDULED, MANUAL }

    public enum Status { RUNNING, COMPLETED, FAILED }

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30, updatable = false)
    private RetentionCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20, updatable = false)
    private Trigger trigger;

    @Column(name = "dry_run", nullable = false, updatable = false)
    private boolean dryRun;

    @Column(name = "retention_months", nullable = false, updatable = false)
    private int retentionMonths;

    /** Última movimentação anterior a este instante torna o devedor candidato. */
    @Column(name = "cutoff", nullable = false, updatable = false)
    private Instant cutoff;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;

    @Column(name = "eligible_count", nullable = false)
    private int eligibleCount;

    @Column(name = "processed_count", nullable = false)
    private int processedCount;

    @Column(name = "retained_count", nullable = false)
    private int retainedCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    protected RetentionRun() {
    }

    public RetentionRun(RetentionCategory category, Trigger trigger, boolean dryRun, int retentionMonths, Instant cutoff,
                        Instant startedAt) {
        Guard.require(category != null && trigger != null && cutoff != null && startedAt != null,
                "retention.run_invalid", "Execução de retenção inválida");
        this.category = category;
        this.trigger = trigger;
        this.dryRun = dryRun;
        this.retentionMonths = retentionMonths;
        this.cutoff = cutoff;
        this.startedAt = startedAt;
        this.status = Status.RUNNING;
    }

    /** Acumula o resultado de um lote. */
    public void record(int candidates, int eligible, int processed, int retained, int failed) {
        Guard.requireState(status == Status.RUNNING, "retention.run_finished", "Execução já encerrada");
        this.candidateCount += candidates;
        this.eligibleCount += eligible;
        this.processedCount += processed;
        this.retainedCount += retained;
        this.failedCount += failed;
    }

    public void complete(Instant now) {
        Guard.requireState(status == Status.RUNNING, "retention.run_finished", "Execução já encerrada");
        this.status = Status.COMPLETED;
        this.finishedAt = now;
    }

    public void fail(String reason, Instant now) {
        Guard.requireState(status == Status.RUNNING, "retention.run_finished", "Execução já encerrada");
        this.status = Status.FAILED;
        this.finishedAt = now;
        this.failureReason = reason == null ? "Falha não identificada"
                : reason.length() > 500 ? reason.substring(0, 500) : reason;
    }

    public RetentionCategory getCategory() {
        return category;
    }

    public Trigger getTrigger() {
        return trigger;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public int getRetentionMonths() {
        return retentionMonths;
    }

    public Instant getCutoff() {
        return cutoff;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Status getStatus() {
        return status;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public int getEligibleCount() {
        return eligibleCount;
    }

    public int getProcessedCount() {
        return processedCount;
    }

    public int getRetainedCount() {
        return retainedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
