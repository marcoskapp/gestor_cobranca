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
 * Estado do assistente numa conversa: etapa de atendimento ({@code episode}, nova a cada devolução ao
 * assistente), identidade confirmada, contadores de turnos e falhas de entendimento, memória de
 * negociação, consumo acumulado do modelo e a marca do turno em andamento ({@code pendingTurnId}:
 * recuperação de turno interrompido por queda da instância).
 */
@Entity
@Table(name = "assistant_session", schema = "assistant")
public class AssistantSession extends TenantScopedEntity {

    /** Identidade confirmada vale entre etapas por este prazo. */
    public static final Duration IDENTITY_VALIDITY = Duration.ofHours(24);

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "creditor_id", nullable = false)
    private UUID creditorId;

    @Column(name = "debtor_id", nullable = false)
    private UUID debtorId;

    @Column(name = "portfolio_id")
    private UUID portfolioId;

    @Column(name = "case_id")
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SessionStatus status;

    @Column(name = "episode", nullable = false)
    private int episode;

    @Column(name = "turns", nullable = false)
    private int turns;

    @Column(name = "total_turns", nullable = false)
    private int totalTurns;

    @Column(name = "misunderstandings", nullable = false)
    private int misunderstandings;

    @Column(name = "introduced", nullable = false)
    private boolean introduced;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_status", nullable = false, length = 20)
    private IdentityStatus identityStatus;

    @Column(name = "identity_attempts", nullable = false)
    private int identityAttempts;

    @Column(name = "identity_verified_at")
    private Instant identityVerifiedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "memory", nullable = false, columnDefinition = "jsonb")
    private String memory;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "cost_amount", nullable = false, precision = 19, scale = 6)
    private BigDecimal costAmount;

    @Column(name = "cost_currency", length = 3)
    private String costCurrency;

    @Column(name = "last_processed_inbound_at")
    private Instant lastProcessedInboundAt;

    @Column(name = "pending_turn_id")
    private UUID pendingTurnId;

    @Column(name = "pending_since")
    private Instant pendingSince;

    @Enumerated(EnumType.STRING)
    @Column(name = "handoff_reason", length = 40)
    private HandoffReason handoffReason;

    @Column(name = "handed_off_at")
    private Instant handedOffAt;

    @Column(name = "last_policy_id")
    private UUID lastPolicyId;

    protected AssistantSession() {
    }

    public AssistantSession(UUID conversationId, UUID creditorId, UUID debtorId, UUID portfolioId, UUID caseId,
                            boolean identityRequired, String emptyMemoryJson) {
        Guard.require(conversationId != null && creditorId != null && debtorId != null, "assistant_session.ids_required",
                "Conversa identificada (devedor e credor) obrigatória");
        this.conversationId = conversationId;
        this.creditorId = creditorId;
        this.debtorId = debtorId;
        this.portfolioId = portfolioId;
        this.caseId = caseId;
        this.status = SessionStatus.ACTIVE;
        this.episode = 1;
        this.identityStatus = identityRequired ? IdentityStatus.PENDING : IdentityStatus.NOT_REQUIRED;
        this.memory = emptyMemoryJson;
        this.costAmount = BigDecimal.ZERO;
    }

    /** Vínculos atuais da conversa (caso aberto depois, carteira). */
    public void relink(UUID portfolio, UUID kase) {
        this.portfolioId = portfolio;
        this.caseId = kase;
    }

    /**
     * Conversa voltou ao assistente depois de transferida: nova etapa com contadores e memória
     * zerados; identidade confirmada há menos de {@link #IDENTITY_VALIDITY} é mantida.
     */
    public void reopen(Instant now, boolean identityRequired, String emptyMemoryJson) {
        Guard.requireState(status == SessionStatus.HANDED_OFF, "assistant_session.active", "Sessão já ativa");
        this.status = SessionStatus.ACTIVE;
        this.episode++;
        this.turns = 0;
        this.misunderstandings = 0;
        this.introduced = false;
        this.handoffReason = null;
        this.handedOffAt = null;
        this.memory = emptyMemoryJson;
        boolean stillVerified = identityStatus == IdentityStatus.VERIFIED && identityVerifiedAt != null
                && identityVerifiedAt.plus(IDENTITY_VALIDITY).isAfter(now);
        if (!stillVerified) {
            this.identityStatus = identityRequired ? IdentityStatus.PENDING : IdentityStatus.NOT_REQUIRED;
            this.identityAttempts = 0;
            this.identityVerifiedAt = null;
        }
    }

    /** Início de um turno: as mensagens até {@code inboundUpTo} passam a ser deste turno. */
    public void begin(UUID turnId, Instant inboundUpTo, Instant now, UUID policyId) {
        Guard.requireState(pendingTurnId == null, "assistant_session.turn_in_progress", "Há turno em andamento");
        this.pendingTurnId = turnId;
        this.pendingSince = now;
        this.lastPolicyId = policyId;
        if (inboundUpTo != null && (lastProcessedInboundAt == null || inboundUpTo.isAfter(lastProcessedInboundAt))) {
            this.lastProcessedInboundAt = inboundUpTo;
        }
    }

    /** Conclusão do turno com o consumo do modelo. */
    public void finish(UUID turnId, long inTokens, long outTokens, BigDecimal cost, String currency) {
        Guard.requireState(turnId.equals(pendingTurnId), "assistant_session.turn_mismatch", "Turno não corresponde");
        this.pendingTurnId = null;
        this.pendingSince = null;
        this.turns++;
        this.totalTurns++;
        this.inputTokens += inTokens;
        this.outputTokens += outTokens;
        if (cost != null && cost.signum() > 0) {
            this.costAmount = costAmount.add(cost);
            if (currency != null) {
                this.costCurrency = currency;
            }
        }
    }

    /**
     * Turno interrompido (queda da instância antes do registro): libera a sessão para recuperação.
     *
     * @return id do turno interrompido
     */
    public UUID abandonPending() {
        Guard.requireState(pendingTurnId != null, "assistant_session.no_pending_turn", "Nenhum turno em andamento");
        UUID id = pendingTurnId;
        this.pendingTurnId = null;
        this.pendingSince = null;
        return id;
    }

    public void introduce() {
        this.introduced = true;
    }

    public void understood() {
        this.misunderstandings = 0;
    }

    /** @return falhas seguidas de entendimento */
    public int misunderstood() {
        return ++misunderstandings;
    }

    public void identityVerified(Instant now) {
        this.identityStatus = IdentityStatus.VERIFIED;
        this.identityVerifiedAt = now;
    }

    /** @return tentativas sem sucesso até agora */
    public int identityFailed(int maxAttempts) {
        this.identityAttempts++;
        if (identityAttempts >= maxAttempts) {
            this.identityStatus = IdentityStatus.FAILED;
        }
        return identityAttempts;
    }

    public void remember(String memoryJson) {
        this.memory = memoryJson;
    }

    public void handedOff(HandoffReason reason, Instant now) {
        this.status = SessionStatus.HANDED_OFF;
        this.handoffReason = reason;
        this.handedOffAt = now;
    }

    public boolean identityConfirmed() {
        return identityStatus == IdentityStatus.VERIFIED || identityStatus == IdentityStatus.NOT_REQUIRED;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public int getEpisode() {
        return episode;
    }

    public int getTurns() {
        return turns;
    }

    public int getTotalTurns() {
        return totalTurns;
    }

    public int getMisunderstandings() {
        return misunderstandings;
    }

    public boolean isIntroduced() {
        return introduced;
    }

    public IdentityStatus getIdentityStatus() {
        return identityStatus;
    }

    public int getIdentityAttempts() {
        return identityAttempts;
    }

    public Instant getIdentityVerifiedAt() {
        return identityVerifiedAt;
    }

    public String getMemory() {
        return memory;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public BigDecimal getCostAmount() {
        return costAmount;
    }

    public String getCostCurrency() {
        return costCurrency;
    }

    public Instant getLastProcessedInboundAt() {
        return lastProcessedInboundAt;
    }

    public UUID getPendingTurnId() {
        return pendingTurnId;
    }

    public Instant getPendingSince() {
        return pendingSince;
    }

    public HandoffReason getHandoffReason() {
        return handoffReason;
    }

    public Instant getHandedOffAt() {
        return handedOffAt;
    }

    public UUID getLastPolicyId() {
        return lastPolicyId;
    }
}
