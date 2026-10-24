package br.com.gestorcobranca.scoring.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Execução de escoragem em lote (uma por data de referência) ou de treino. A escoragem percorre os
 * casos em páginas (jobs encadeados com cursor); contadores acumulam por página.
 */
@Entity
@Table(name = "run", schema = "scoring")
public class ScoringRun extends TenantScopedEntity {

    public enum Kind { BATCH, TRAINING }

    public enum Status { RUNNING, COMPLETED, FAILED, INSUFFICIENT_DATA }

    public enum Trigger { SCHEDULED, MANUAL }

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 20)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_trigger", nullable = false, updatable = false, length = 20)
    private Trigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "reference_date", nullable = false, updatable = false)
    private LocalDate referenceDate;

    @Column(name = "reference_at", nullable = false, updatable = false)
    private Instant referenceAt;

    @Column(name = "snapshot", nullable = false, updatable = false)
    private boolean snapshot;

    @Column(name = "outcome_days", nullable = false, updatable = false)
    private int outcomeDays;

    @Column(name = "champion_model_id", updatable = false)
    private UUID championModelId;

    @Column(name = "challenger_model_id", updatable = false)
    private UUID challengerModelId;

    @Column(name = "result_model_id")
    private UUID resultModelId;

    @Column(name = "cursor_case_id")
    private UUID cursorCaseId;

    @Column(name = "scored", nullable = false)
    private int scored;

    @Column(name = "skipped", nullable = false)
    private int skipped;

    @Column(name = "published", nullable = false)
    private int published;

    @Column(name = "band_changes", nullable = false)
    private int bandChanges;

    @Column(name = "failures", nullable = false)
    private int failures;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details")
    private String details;

    @Column(name = "requested_by", updatable = false)
    private UUID requestedBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "cohort_measured_at")
    private Instant cohortMeasuredAt;

    protected ScoringRun() {
    }

    public ScoringRun(Kind kind, Trigger trigger, LocalDate referenceDate, Instant referenceAt, boolean snapshot,
                      int outcomeDays, UUID championModelId, UUID challengerModelId, String details, UUID requestedBy) {
        Guard.require(kind != null && trigger != null && referenceDate != null && referenceAt != null,
                "scoring.run.invalid", "Execução incompleta");
        this.kind = kind;
        this.trigger = trigger;
        this.status = Status.RUNNING;
        this.referenceDate = referenceDate;
        this.referenceAt = referenceAt;
        this.snapshot = snapshot;
        this.outcomeDays = outcomeDays;
        this.championModelId = championModelId;
        this.challengerModelId = challengerModelId;
        this.details = details;
        this.requestedBy = requestedBy;
        this.startedAt = referenceAt;
    }

    /** Página processada: acumula contadores e avança o cursor. */
    public void pageDone(UUID cursor, int scored, int skipped, int published, int bandChanges, int failures) {
        Guard.requireState(status == Status.RUNNING, "scoring.run.not_running", "Execução já encerrada");
        this.cursorCaseId = cursor;
        this.scored += scored;
        this.skipped += skipped;
        this.published += published;
        this.bandChanges += bandChanges;
        this.failures += failures;
    }

    public void finish(Status finalStatus, String details, UUID resultModelId, Instant now) {
        Guard.requireState(status == Status.RUNNING, "scoring.run.not_running", "Execução já encerrada");
        Guard.require(finalStatus != Status.RUNNING, "scoring.run.status_invalid", "Situação final inválida");
        this.status = finalStatus;
        if (details != null) {
            this.details = details;
        }
        this.resultModelId = resultModelId;
        this.finishedAt = now;
    }

    public void cohortMeasured(Instant now) {
        this.cohortMeasuredAt = now;
    }

    public Kind getKind() {
        return kind;
    }

    public Trigger getTrigger() {
        return trigger;
    }

    public Status getStatus() {
        return status;
    }

    public LocalDate getReferenceDate() {
        return referenceDate;
    }

    public Instant getReferenceAt() {
        return referenceAt;
    }

    public boolean isSnapshot() {
        return snapshot;
    }

    public int getOutcomeDays() {
        return outcomeDays;
    }

    public UUID getChampionModelId() {
        return championModelId;
    }

    public UUID getChallengerModelId() {
        return challengerModelId;
    }

    public UUID getResultModelId() {
        return resultModelId;
    }

    public UUID getCursorCaseId() {
        return cursorCaseId;
    }

    public int getScored() {
        return scored;
    }

    public int getSkipped() {
        return skipped;
    }

    public int getPublished() {
        return published;
    }

    public int getBandChanges() {
        return bandChanges;
    }

    public int getFailures() {
        return failures;
    }

    public String getDetails() {
        return details;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getCohortMeasuredAt() {
        return cohortMeasuredAt;
    }
}
