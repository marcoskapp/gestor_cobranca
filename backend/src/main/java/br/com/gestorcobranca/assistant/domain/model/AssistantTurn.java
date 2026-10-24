package br.com.gestorcobranca.assistant.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Trilha de um turno do assistente (append-only por privilégio e gatilho): entrada recebida,
 * contexto enviado ao modelo (cifrado, com SHA-256 e versão do esquema), resposta, guardrails
 * acionados, resultado, motivo e origem da transferência, consumo (tokens, custo, latência) e as
 * versões de política e prompt. Somente o descarte do conteúdo (retenção {@code MESSAGE_CONTENT} e
 * anonimização do devedor) altera a linha, por comando próprio; as chamadas de ferramenta ficam em
 * {@link ToolCallRecord}.
 */
@Entity
@Table(name = "assistant_turn", schema = "assistant")
public class AssistantTurn extends TenantScopedEntity {

    /** v2: parcelas em aberto ({@code I1}…) e indicador do Pix na conversa. */
    public static final int CONTEXT_SCHEMA_VERSION = 2;

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

    @Column(name = "policy_version", updatable = false)
    private Integer policyVersion;

    @Column(name = "prompt_code", length = 40, updatable = false)
    private String promptCode;

    @Column(name = "prompt_version", updatable = false)
    private Integer promptVersion;

    @Column(name = "prompt_sha256", length = 64, updatable = false)
    private String promptSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 30, updatable = false)
    private AssistantMode mode;

    @Column(name = "episode", nullable = false, updatable = false)
    private int episode;

    @Column(name = "turn_number", nullable = false, updatable = false)
    private int turnNumber;

    @Column(name = "trigger_message_id", updatable = false)
    private UUID triggerMessageId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_message_ids", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String inputMessageIds;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "input_text_encrypted", length = 100000)
    private String inputText;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "context_encrypted", length = 400000)
    private String context;

    @Column(name = "context_sha256", length = 64, updatable = false)
    private String contextSha256;

    @Column(name = "context_version", nullable = false, updatable = false)
    private int contextVersion;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "output_text_encrypted", length = 100000)
    private String outputText;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 30, updatable = false)
    private TurnOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "handoff_reason", length = 40, updatable = false)
    private HandoffReason handoffReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", length = 20, updatable = false)
    private TriggerSource triggerSource;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "guardrails", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String guardrails;

    @Column(name = "intent", length = 30, updatable = false)
    private String intent;

    @Column(name = "confidence", precision = 5, scale = 4, updatable = false)
    private BigDecimal confidence;

    @Column(name = "provider", length = 40, updatable = false)
    private String provider;

    @Column(name = "model", length = 80, updatable = false)
    private String model;

    @Column(name = "model_calls", nullable = false, updatable = false)
    private int modelCalls;

    @Column(name = "tool_calls", nullable = false, updatable = false)
    private int toolCalls;

    @Column(name = "input_tokens", nullable = false, updatable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false, updatable = false)
    private long outputTokens;

    @Column(name = "cost_amount", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal costAmount;

    @Column(name = "cost_currency", length = 3, updatable = false)
    private String costCurrency;

    @Column(name = "model_latency_ms", nullable = false, updatable = false)
    private long modelLatencyMs;

    @Column(name = "total_latency_ms", nullable = false, updatable = false)
    private long totalLatencyMs;

    @Column(name = "outbound_message_id", updatable = false)
    private UUID outboundMessageId;

    @Column(name = "draft_id", updatable = false)
    private UUID draftId;

    @Column(name = "proposal_id", updatable = false)
    private UUID proposalId;

    @Column(name = "agreement_id", updatable = false)
    private UUID agreementId;

    @Column(name = "error_code", length = 80, updatable = false)
    private String errorCode;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false, updatable = false)
    private Instant finishedAt;

    @Column(name = "content_purged_at")
    private Instant contentPurgedAt;

    protected AssistantTurn() {
    }

    /** Dados completos do turno, montados pelo orquestrador. */
    public record Record(UUID sessionId, UUID conversationId, UUID creditorId, UUID portfolioId,
                         UUID debtorId, UUID caseId, UUID policyId, Integer policyVersion, String promptCode,
                         Integer promptVersion, String promptSha256, AssistantMode mode, int episode, int turnNumber,
                         UUID triggerMessageId, String inputMessageIdsJson, String inputText, String context,
                         String contextSha256, String outputText, TurnOutcome outcome, HandoffReason handoffReason,
                         TriggerSource triggerSource, String guardrailsJson, String intent, BigDecimal confidence,
                         String provider, String model, int modelCalls, int toolCalls, long inputTokens,
                         long outputTokens, BigDecimal costAmount, String costCurrency, long modelLatencyMs,
                         long totalLatencyMs, UUID outboundMessageId, UUID draftId, UUID proposalId, UUID agreementId,
                         String errorCode, Instant startedAt, Instant finishedAt) {
    }

    public AssistantTurn(Record r) {
        Guard.require(r.sessionId() != null && r.conversationId() != null && r.outcome() != null && r.mode() != null,
                "assistant_turn.invalid", "Turno incompleto");
        this.sessionId = r.sessionId();
        this.conversationId = r.conversationId();
        this.creditorId = r.creditorId();
        this.portfolioId = r.portfolioId();
        this.debtorId = r.debtorId();
        this.caseId = r.caseId();
        this.policyId = r.policyId();
        this.policyVersion = r.policyVersion();
        this.promptCode = r.promptCode();
        this.promptVersion = r.promptVersion();
        this.promptSha256 = r.promptSha256();
        this.mode = r.mode();
        this.episode = r.episode();
        this.turnNumber = r.turnNumber();
        this.triggerMessageId = r.triggerMessageId();
        this.inputMessageIds = r.inputMessageIdsJson() == null ? "[]" : r.inputMessageIdsJson();
        this.inputText = r.inputText();
        this.context = r.context();
        this.contextSha256 = r.contextSha256();
        this.contextVersion = CONTEXT_SCHEMA_VERSION;
        this.outputText = r.outputText();
        this.outcome = r.outcome();
        this.handoffReason = r.handoffReason();
        this.triggerSource = r.triggerSource();
        this.guardrails = r.guardrailsJson() == null ? "[]" : r.guardrailsJson();
        this.intent = r.intent();
        this.confidence = r.confidence();
        this.provider = r.provider();
        this.model = r.model();
        this.modelCalls = r.modelCalls();
        this.toolCalls = r.toolCalls();
        this.inputTokens = r.inputTokens();
        this.outputTokens = r.outputTokens();
        this.costAmount = r.costAmount() == null ? BigDecimal.ZERO : r.costAmount();
        this.costCurrency = r.costCurrency();
        this.modelLatencyMs = r.modelLatencyMs();
        this.totalLatencyMs = r.totalLatencyMs();
        this.outboundMessageId = r.outboundMessageId();
        this.draftId = r.draftId();
        this.proposalId = r.proposalId();
        this.agreementId = r.agreementId();
        this.errorCode = r.errorCode();
        this.startedAt = r.startedAt();
        this.finishedAt = r.finishedAt();
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

    public Integer getPolicyVersion() {
        return policyVersion;
    }

    public String getPromptCode() {
        return promptCode;
    }

    public Integer getPromptVersion() {
        return promptVersion;
    }

    public String getPromptSha256() {
        return promptSha256;
    }

    public AssistantMode getMode() {
        return mode;
    }

    public int getEpisode() {
        return episode;
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public UUID getTriggerMessageId() {
        return triggerMessageId;
    }

    public String getInputMessageIds() {
        return inputMessageIds;
    }

    public String getInputText() {
        return inputText;
    }

    public String getContext() {
        return context;
    }

    public String getContextSha256() {
        return contextSha256;
    }

    public int getContextVersion() {
        return contextVersion;
    }

    public String getOutputText() {
        return outputText;
    }

    public TurnOutcome getOutcome() {
        return outcome;
    }

    public HandoffReason getHandoffReason() {
        return handoffReason;
    }

    public TriggerSource getTriggerSource() {
        return triggerSource;
    }

    public String getGuardrails() {
        return guardrails;
    }

    public String getIntent() {
        return intent;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public int getModelCalls() {
        return modelCalls;
    }

    public int getToolCalls() {
        return toolCalls;
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

    public long getModelLatencyMs() {
        return modelLatencyMs;
    }

    public long getTotalLatencyMs() {
        return totalLatencyMs;
    }

    public UUID getOutboundMessageId() {
        return outboundMessageId;
    }

    public UUID getDraftId() {
        return draftId;
    }

    public UUID getProposalId() {
        return proposalId;
    }

    public UUID getAgreementId() {
        return agreementId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getContentPurgedAt() {
        return contentPurgedAt;
    }
}
