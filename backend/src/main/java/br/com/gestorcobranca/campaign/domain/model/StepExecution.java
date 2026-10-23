package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Execução de uma etapa de uma inscrição (append-only; só a situação da mensagem enviada é
 * atualizada). Chave idempotente {@code campaign:<inscrição>:<etapa>:<iteração>}: única entre as
 * execuções efetivas e puladas (índice parcial) e também enviada ao communication, que devolve a
 * mensagem já criada em vez de duplicá-la. Falhas ficam registradas como tentativas.
 */
@Entity
@Table(name = "step_execution", schema = "campaign")
public class StepExecution extends TenantScopedEntity {

    /** Código usado nas transições por resposta executadas pelo job (entregar ao assistente, fila). */
    public static final String REPLY_STEP = "REPLY";

    @Column(name = "enrollment_id", nullable = false, updatable = false)
    private UUID enrollmentId;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Column(name = "cadence_id", nullable = false, updatable = false)
    private UUID cadenceId;

    @Column(name = "step_code", nullable = false, length = 40, updatable = false)
    private String stepCode;

    @Column(name = "iteration", nullable = false, updatable = false)
    private int iteration;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 30, updatable = false)
    private ActionType actionType;

    @Column(name = "channel", length = 20, updatable = false)
    private String channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20, updatable = false)
    private StepOutcome outcome;

    @Column(name = "reason", length = 200, updatable = false)
    private String reason;

    @Column(name = "reason_detail", length = 500, updatable = false)
    private String reasonDetail;

    @Column(name = "message_id", updatable = false)
    private UUID messageId;

    @Column(name = "message_status", length = 20)
    private String messageStatus;

    @Column(name = "task_id", updatable = false)
    private UUID taskId;

    @Column(name = "variant_code", length = 40, updatable = false)
    private String variantCode;

    @Column(name = "executed_at", nullable = false, updatable = false)
    private Instant executedAt;

    @Column(name = "idempotency_key", nullable = false, length = 150, updatable = false)
    private String idempotencyKey;

    protected StepExecution() {
    }

    /** Identificação da execução. */
    public record Key(UUID enrollmentId, UUID campaignId, UUID cadenceId, String stepCode, int iteration,
                      ActionType actionType, String channel, String variantCode) {

        public String idempotencyKey() {
            return keyFor(enrollmentId, stepCode, iteration);
        }
    }

    public static String keyFor(UUID enrollmentId, String stepCode, int iteration) {
        return "campaign:" + enrollmentId + ":" + stepCode.toLowerCase(Locale.ROOT) + ":" + iteration;
    }

    public static StepExecution executed(Key key, UUID messageId, String messageStatus, UUID taskId, Instant now) {
        StepExecution s = of(key, StepOutcome.EXECUTED, null, null, now);
        s.messageId = messageId;
        s.messageStatus = messageStatus;
        s.taskId = taskId;
        return s;
    }

    public static StepExecution skipped(Key key, String reason, String detail, UUID messageId, Instant now) {
        StepExecution s = of(key, StepOutcome.SKIPPED, reason, detail, now);
        s.messageId = messageId;
        return s;
    }

    public static StepExecution failed(Key key, String code, String detail, Instant now) {
        return of(key, StepOutcome.FAILED, code, detail, now);
    }

    private static StepExecution of(Key key, StepOutcome outcome, String reason, String detail, Instant now) {
        StepExecution s = new StepExecution();
        s.enrollmentId = key.enrollmentId();
        s.campaignId = key.campaignId();
        s.cadenceId = key.cadenceId();
        s.stepCode = key.stepCode();
        s.iteration = key.iteration();
        s.actionType = key.actionType();
        s.channel = key.channel();
        s.variantCode = key.variantCode();
        s.outcome = outcome;
        s.reason = truncate(reason, 200);
        s.reasonDetail = truncate(detail, 500);
        s.executedAt = now;
        s.idempotencyKey = key.idempotencyKey();
        return s;
    }

    /** Situação da mensagem informada pelo communication (nunca regride para QUEUED/SENT). */
    public boolean updateMessageStatus(String status) {
        if (status == null || status.equals(messageStatus) || !advances(messageStatus, status)) {
            return false;
        }
        this.messageStatus = status;
        return true;
    }

    private static boolean advances(String current, String next) {
        return rank(next) > rank(current);
    }

    private static int rank(String status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case "QUEUED" -> 1;
            case "SENT" -> 2;
            case "DELIVERED" -> 3;
            case "READ" -> 4;
            case "FAILED" -> 5;
            default -> 0;
        };
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public UUID getEnrollmentId() {
        return enrollmentId;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public UUID getCadenceId() {
        return cadenceId;
    }

    public String getStepCode() {
        return stepCode;
    }

    public int getIteration() {
        return iteration;
    }

    public ActionType getActionType() {
        return actionType;
    }

    public String getChannel() {
        return channel;
    }

    public StepOutcome getOutcome() {
        return outcome;
    }

    public String getReason() {
        return reason;
    }

    public String getReasonDetail() {
        return reasonDetail;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public String getMessageStatus() {
        return messageStatus;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public String getVariantCode() {
        return variantCode;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
