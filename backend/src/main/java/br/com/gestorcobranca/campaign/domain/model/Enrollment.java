package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Inscrição: a execução de uma campanha para um caso de cobrança (devedor × carteira), na versão da
 * régua com que começou. Guarda a próxima etapa (código + iteração — a chave idempotente das
 * execuções), a pausa e o encerramento.
 * <pre>
 *   ACTIVE ⇄ PAUSED        (pausas automáticas por evento e manuais; retomada automática quando a causa cessa)
 *   ACTIVE → FAILED → ACTIVE (erro numa etapa; reprocessamento com a mesma chave idempotente)
 *   HOLDOUT                (grupo de controle do teste A/B: nenhuma ação até o fim do horizonte)
 *   * → ENDED              (régua esgotada/concluída, resposta, entrega ao assistente/fila, caso encerrado…)
 * </pre>
 * {@code observeUntil} delimita a atribuição de resultados: nulo enquanto em andamento; no fim,
 * fim + janela de atribuição da campanha; no controle, inscrição + horizonte do experimento.
 */
@Entity
@Table(name = "enrollment", schema = "campaign")
public class Enrollment extends TenantScopedEntity {

    /** Limite de execuções de etapa por inscrição (protege contra laços de "ir para etapa"). */
    public static final int MAX_STEP_RUNS = 100;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Column(name = "cadence_id", nullable = false, updatable = false)
    private UUID cadenceId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Column(name = "exclusivity_group", length = 60, updatable = false)
    private String exclusivityGroup;

    @Column(name = "priority", nullable = false, updatable = false)
    private int priority;

    @Column(name = "experiment_id", updatable = false)
    private UUID experimentId;

    @Column(name = "variant_code", length = 40, updatable = false)
    private String variantCode;

    @Column(name = "control", nullable = false, updatable = false)
    private boolean control;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EnrollmentStatus status;

    @Column(name = "enrolled_at", nullable = false, updatable = false)
    private Instant enrolledAt;

    @Column(name = "next_step_code", length = 40)
    private String nextStepCode;

    @Column(name = "next_step_iteration", nullable = false)
    private int nextStepIteration;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    /** Quando a etapa corrente ficou devida (limite do adiamento por horário/limite do porteiro). */
    @Column(name = "step_due_at")
    private Instant stepDueAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "pending_reply_action", length = 30)
    private ReplyTransition pendingReplyAction;

    @Column(name = "last_step_code", length = 40)
    private String lastStepCode;

    @Column(name = "last_step_at")
    private Instant lastStepAt;

    @Column(name = "last_message_id")
    private UUID lastMessageId;

    @Column(name = "steps_executed", nullable = false)
    private int stepsExecuted;

    @Column(name = "steps_skipped", nullable = false)
    private int stepsSkipped;

    @Column(name = "step_runs", nullable = false)
    private int stepRuns;

    @Enumerated(EnumType.STRING)
    @Column(name = "pause_reason", length = 30)
    private PauseReason pauseReason;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "resume_at")
    private Instant resumeAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 40)
    private EndReason endReason;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "observe_until")
    private Instant observeUntil;

    @Column(name = "replied_at")
    private Instant repliedAt;

    @Column(name = "effective_contact_at")
    private Instant effectiveContactAt;

    @Column(name = "last_error_code", length = 120)
    private String lastErrorCode;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    protected Enrollment() {
    }

    /** Dados de origem da inscrição. @param variantCode braço do teste A/B (nulo sem experimento) */
    public record Origin(UUID campaignId, UUID cadenceId, UUID caseId, UUID debtorId, UUID creditorId,
                         UUID portfolioId, String exclusivityGroup, int priority, UUID experimentId,
                         String variantCode) {
    }

    /** Inscrição tratada: a primeira etapa roda após a sua espera. */
    public static Enrollment start(Origin origin, CadenceStep firstStep, Instant now) {
        Enrollment e = base(origin, now);
        e.exclusivityGroup = origin.exclusivityGroup();
        e.status = EnrollmentStatus.ACTIVE;
        e.nextStepCode = firstStep.code();
        e.nextStepIteration = 1;
        e.nextRunAt = now.plus(Duration.ofMinutes(firstStep.waitMinutes()));
        e.stepDueAt = e.nextRunAt;
        return e;
    }

    /** Grupo de controle: nenhuma ação, sem exclusividade, observado até o fim do horizonte. */
    public static Enrollment holdout(Origin origin, Instant now, int observationDays) {
        Enrollment e = base(origin, now);
        e.control = true;
        e.status = EnrollmentStatus.HOLDOUT;
        e.observeUntil = now.plus(Duration.ofDays(observationDays));
        return e;
    }

    private static Enrollment base(Origin o, Instant now) {
        Enrollment e = new Enrollment();
        e.campaignId = o.campaignId();
        e.cadenceId = o.cadenceId();
        e.caseId = o.caseId();
        e.debtorId = o.debtorId();
        e.creditorId = o.creditorId();
        e.portfolioId = o.portfolioId();
        e.priority = o.priority();
        e.experimentId = o.experimentId();
        e.variantCode = o.variantCode();
        e.enrolledAt = now;
        return e;
    }

    public boolean isRunning() {
        return status == EnrollmentStatus.ACTIVE || status == EnrollmentStatus.PAUSED || status == EnrollmentStatus.FAILED;
    }

    /** Execução de etapa registrada (executada ou pulada). Falhas usam {@link #fail}. */
    public void recordStep(String stepCode, StepOutcome outcome, UUID messageId, Instant now) {
        Guard.requireState(status == EnrollmentStatus.ACTIVE, "enrollment.not_active", "Inscrição não está ativa");
        this.lastStepCode = stepCode;
        this.lastStepAt = now;
        this.stepRuns++;
        if (outcome == StepOutcome.EXECUTED) {
            stepsExecuted++;
        } else if (outcome == StepOutcome.SKIPPED) {
            stepsSkipped++;
        }
        if (messageId != null) {
            this.lastMessageId = messageId;
        }
        this.lastErrorCode = null;
        this.lastErrorAt = null;
    }

    public boolean runsExhausted() {
        return stepRuns >= MAX_STEP_RUNS;
    }

    public void scheduleNext(String stepCode, int iteration, Instant runAt) {
        this.nextStepCode = stepCode;
        this.nextStepIteration = iteration;
        this.nextRunAt = runAt;
        this.stepDueAt = runAt;
    }

    /** Próxima iteração (número sequencial da execução de etapa na inscrição — parte da chave idempotente). */
    public int nextIteration() {
        return stepRuns + 1;
    }

    /** Adia a mesma etapa (janela da etapa ou horário/limite do porteiro). */
    public void defer(Instant runAt) {
        this.nextRunAt = runAt;
    }

    /**
     * Pausa. Inscrição já pausada mantém o primeiro motivo (a retomada exige que ele cesse).
     *
     * @param resumeAt retomada automática a partir de (nulo = quando a causa cessar ou manual)
     * @return {@code true} se a inscrição passou a pausada
     */
    public boolean pause(PauseReason reason, Instant now, Instant resumeAt) {
        if (status != EnrollmentStatus.ACTIVE && status != EnrollmentStatus.FAILED) {
            return false;
        }
        this.status = EnrollmentStatus.PAUSED;
        this.pauseReason = reason;
        this.pausedAt = now;
        this.resumeAt = resumeAt;
        return true;
    }

    /** Retoma a inscrição pausada; a próxima etapa roda a partir de agora (ou do horário já previsto). */
    public void resume(Instant now) {
        Guard.requireState(status == EnrollmentStatus.PAUSED, "enrollment.not_paused", "Inscrição não está pausada");
        this.status = EnrollmentStatus.ACTIVE;
        this.pauseReason = null;
        this.pausedAt = null;
        this.resumeAt = null;
        if (nextRunAt == null || nextRunAt.isBefore(now)) {
            this.nextRunAt = now;
        }
        this.stepDueAt = nextRunAt;
    }

    /** Troca o motivo de uma inscrição já pausada (a causa mudou, ex.: pagamento seguido de acordo). */
    public void changePauseReason(PauseReason reason) {
        Guard.requireState(status == EnrollmentStatus.PAUSED, "enrollment.not_paused", "Inscrição não está pausada");
        this.pauseReason = reason;
        this.resumeAt = null;
    }

    /** Erro na etapa corrente: aguarda reprocessamento (mesma etapa e iteração). */
    public void fail(String errorCode, Instant now) {
        Guard.requireState(status == EnrollmentStatus.ACTIVE, "enrollment.not_active", "Inscrição não está ativa");
        this.status = EnrollmentStatus.FAILED;
        this.lastErrorCode = errorCode == null ? "UNKNOWN" : errorCode.length() > 120 ? errorCode.substring(0, 120) : errorCode;
        this.lastErrorAt = now;
    }

    public void reprocess(Instant now) {
        Guard.requireState(status == EnrollmentStatus.FAILED, "enrollment.not_failed", "Inscrição sem falha a reprocessar");
        this.status = EnrollmentStatus.ACTIVE;
        this.nextRunAt = now;
        this.stepDueAt = now;
    }

    /** Encerra e abre a janela de atribuição de resultados posteriores. */
    public void end(EndReason reason, Instant now, int attributionWindowDays) {
        if (status == EnrollmentStatus.ENDED) {
            return;
        }
        this.status = EnrollmentStatus.ENDED;
        this.endReason = reason;
        this.endedAt = now;
        this.nextStepCode = null;
        this.nextRunAt = null;
        this.pendingReplyAction = null;
        this.pauseReason = null;
        this.resumeAt = null;
        if (!control) {
            this.observeUntil = now.plus(Duration.ofDays(attributionWindowDays));
        }
    }

    /** @return {@code true} na primeira resposta desta inscrição */
    public boolean registerReply(Instant at) {
        if (repliedAt != null) {
            return false;
        }
        this.repliedAt = at;
        return true;
    }

    public void registerEffectiveContact(Instant at) {
        if (effectiveContactAt == null) {
            this.effectiveContactAt = at;
        }
    }

    /** Ação de transição por resposta a executar pelo job de etapa (entregar ao assistente, mover para a fila). */
    public void requestReplyAction(ReplyTransition action, Instant now) {
        Guard.require(action == ReplyTransition.HAND_TO_ASSISTANT || action == ReplyTransition.MOVE_TO_QUEUE,
                "enrollment.reply_action_invalid", "Ação de resposta inválida");
        this.pendingReplyAction = action;
        this.nextRunAt = now;
        this.stepDueAt = now;
    }

    public void clearReplyAction() {
        this.pendingReplyAction = null;
    }

    /** O resultado pode ser atribuído a esta inscrição no instante informado. */
    public boolean observes(Instant at) {
        return !at.isBefore(enrolledAt) && (observeUntil == null || !at.isAfter(observeUntil));
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public UUID getCadenceId() {
        return cadenceId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public String getExclusivityGroup() {
        return exclusivityGroup;
    }

    public int getPriority() {
        return priority;
    }

    public UUID getExperimentId() {
        return experimentId;
    }

    public String getVariantCode() {
        return variantCode;
    }

    public boolean isControl() {
        return control;
    }

    public EnrollmentStatus getStatus() {
        return status;
    }

    public Instant getEnrolledAt() {
        return enrolledAt;
    }

    public String getNextStepCode() {
        return nextStepCode;
    }

    public int getNextStepIteration() {
        return nextStepIteration;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public Instant getStepDueAt() {
        return stepDueAt;
    }

    public ReplyTransition getPendingReplyAction() {
        return pendingReplyAction;
    }

    public String getLastStepCode() {
        return lastStepCode;
    }

    public Instant getLastStepAt() {
        return lastStepAt;
    }

    public UUID getLastMessageId() {
        return lastMessageId;
    }

    public int getStepsExecuted() {
        return stepsExecuted;
    }

    public int getStepsSkipped() {
        return stepsSkipped;
    }

    public int getStepRuns() {
        return stepRuns;
    }

    public PauseReason getPauseReason() {
        return pauseReason;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public Instant getResumeAt() {
        return resumeAt;
    }

    public EndReason getEndReason() {
        return endReason;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public Instant getObserveUntil() {
        return observeUntil;
    }

    public Instant getRepliedAt() {
        return repliedAt;
    }

    public Instant getEffectiveContactAt() {
        return effectiveContactAt;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public Instant getLastErrorAt() {
        return lastErrorAt;
    }
}
