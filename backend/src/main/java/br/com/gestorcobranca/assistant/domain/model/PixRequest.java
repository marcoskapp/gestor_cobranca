package br.com.gestorcobranca.assistant.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Pedido de Pix na conversa (ferramenta {@code generate_payment}): a avaliação dos critérios no servidor
 * ({@code criteria}, imutável — gatilho no banco), a decisão ({@link PixDecision}) e, quando gerado, a
 * instrução do payment, a entrega do código ao devedor e o pagamento (conversão Pix emitido → pago).
 * Gravado com o turno; nunca guarda o "copia e cola" nem dados pessoais.
 */
@Entity
@Table(name = "pix_request", schema = "assistant")
public class PixRequest extends TenantScopedEntity {

    /** Janela do indicador "pago em até 24 h". */
    public static final Duration PAID_WINDOW = Duration.ofHours(24);

    @Column(name = "turn_id", nullable = false, updatable = false)
    private UUID turnId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "case_id", updatable = false)
    private UUID caseId;

    @Column(name = "policy_id", updatable = false)
    private UUID policyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 30, updatable = false)
    private AssistantMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 20, updatable = false)
    private PixDecision decision;

    @Column(name = "denial_code", length = 40, updatable = false)
    private String denialCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "criteria", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String criteria;

    @Column(name = "agreement_id", updatable = false)
    private UUID agreementId;

    @Column(name = "installment_id", updatable = false)
    private UUID installmentId;

    @Column(name = "installment_number", updatable = false)
    private Integer installmentNumber;

    @Column(name = "installment_kind", length = 20, updatable = false)
    private String installmentKind;

    @Column(name = "amount", precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "instruction_id", updatable = false)
    private UUID instructionId;

    @Column(name = "reused_instruction", nullable = false, updatable = false)
    private boolean reusedInstruction;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", length = 20)
    private PixDeliveryStatus deliveryStatus;

    @Column(name = "delivery_code", length = 60)
    private String deliveryCode;

    @Column(name = "delivery_message_id")
    private UUID deliveryMessageId;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    protected PixRequest() {
    }

    /**
     * Avaliação registrada no turno.
     *
     * @param instruction instrução gerada (só {@code ISSUED})
     */
    public PixRequest(Scope scope, PixDecision decision, String denialCode, String criteriaJson, Target target,
                      Instruction instruction, Instant requestedAt) {
        Guard.require(scope != null && decision != null && criteriaJson != null && requestedAt != null,
                "pix_request.required", "Pedido de Pix incompleto");
        Guard.require((decision == PixDecision.DENIED) == (denialCode != null), "pix_request.denial_code",
                "Recusa exige o código do critério");
        Guard.require((decision == PixDecision.ISSUED) == (instruction != null), "pix_request.instruction",
                "Pix gerado exige a instrução de pagamento");
        this.sessionId = scope.sessionId();
        this.conversationId = scope.conversationId();
        this.creditorId = scope.creditorId();
        this.portfolioId = scope.portfolioId();
        this.debtorId = scope.debtorId();
        this.caseId = scope.caseId();
        this.policyId = scope.policyId();
        this.mode = scope.mode();
        this.decision = decision;
        this.denialCode = denialCode;
        this.criteria = criteriaJson;
        if (target != null) {
            this.agreementId = target.agreementId();
            this.installmentId = target.installmentId();
            this.installmentNumber = target.installmentNumber();
            this.installmentKind = target.kind();
            this.amount = target.amount();
        }
        if (instruction != null) {
            this.instructionId = instruction.instructionId();
            this.reusedInstruction = instruction.reused();
            this.expiresAt = instruction.expiresAt();
            this.deliveryStatus = PixDeliveryStatus.AWAITING;
        }
        this.requestedAt = requestedAt;
    }

    /** Conversa, devedor e política do pedido. */
    public record Scope(UUID sessionId, UUID conversationId, UUID creditorId, UUID portfolioId, UUID debtorId,
                        UUID caseId, UUID policyId, AssistantMode mode) {
    }

    /** Parcela escolhida (valores do motor). @param kind SINGLE, DOWN_PAYMENT ou INSTALLMENT */
    public record Target(UUID agreementId, UUID installmentId, int installmentNumber, String kind, BigDecimal amount) {
    }

    public record Instruction(UUID instructionId, boolean reused, Instant expiresAt) {
    }

    /** Vínculo com o turno, gravado na mesma transação. */
    public void attachTo(UUID turn) {
        Guard.requireState(this.turnId == null, "pix_request.attached", "Pedido já vinculado a um turno");
        this.turnId = turn;
    }

    /** O turno não respondeu ao devedor: a instrução foi cancelada e nada será enviado. */
    public void cancelDelivery(String code) {
        if (deliveryStatus != null && deliveryStatus.open()) {
            this.deliveryStatus = PixDeliveryStatus.CANCELLED;
            this.deliveryCode = code;
        }
    }

    public void delivered(UUID messageId, Instant at) {
        Guard.requireState(deliveryStatus == PixDeliveryStatus.AWAITING, "pix_request.not_awaiting",
                "Pix não aguarda envio");
        this.deliveryStatus = PixDeliveryStatus.DELIVERED;
        this.deliveryMessageId = messageId;
        this.deliveredAt = at;
    }

    public void notDelivered(String code, UUID messageId) {
        Guard.requireState(deliveryStatus == PixDeliveryStatus.AWAITING, "pix_request.not_awaiting",
                "Pix não aguarda envio");
        this.deliveryStatus = PixDeliveryStatus.NOT_DELIVERED;
        this.deliveryCode = code;
        this.deliveryMessageId = messageId;
    }

    /** @return pago pela primeira vez agora */
    public boolean paid(Instant at) {
        if (paidAt != null || at == null) {
            return false;
        }
        this.paidAt = at;
        return true;
    }

    public boolean paidWithinWindow() {
        return paidAt != null && !paidAt.isAfter(requestedAt.plus(PAID_WINDOW));
    }

    public UUID getTurnId() {
        return turnId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public AssistantMode getMode() {
        return mode;
    }

    public PixDecision getDecision() {
        return decision;
    }

    public String getDenialCode() {
        return denialCode;
    }

    public String getCriteria() {
        return criteria;
    }

    public UUID getAgreementId() {
        return agreementId;
    }

    public UUID getInstallmentId() {
        return installmentId;
    }

    public Integer getInstallmentNumber() {
        return installmentNumber;
    }

    public String getInstallmentKind() {
        return installmentKind;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public UUID getInstructionId() {
        return instructionId;
    }

    public boolean isReusedInstruction() {
        return reusedInstruction;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public PixDeliveryStatus getDeliveryStatus() {
        return deliveryStatus;
    }

    public String getDeliveryCode() {
        return deliveryCode;
    }

    public UUID getDeliveryMessageId() {
        return deliveryMessageId;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }
}
