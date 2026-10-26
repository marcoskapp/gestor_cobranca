package br.com.gestorcobranca.helpdesk.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Conversa de um usuário com o chat de suporte. Guarda o consumo acumulado (base do limite por conversa) e o
 * livro de referências opacas ({@code D1}, {@code A1}…) que a IA usa no lugar dos ids e dos dados pessoais —
 * só ids internos, nunca nome ou documento.
 */
@Entity
@DynamicUpdate
@Table(name = "chat_conversation", schema = "helpdesk")
public class ChatConversation extends TenantScopedEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "screen_key", length = 64)
    private String screenKey;

    @Column(name = "turns", nullable = false)
    private int turns;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "cost_amount", nullable = false, precision = 19, scale = 6)
    private BigDecimal costAmount;

    @Column(name = "cost_currency", length = 3)
    private String costCurrency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "references_json", nullable = false, columnDefinition = "jsonb")
    private String referencesJson;

    protected ChatConversation() {
    }

    public ChatConversation(UUID userId, String screenKey, Instant now) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.startedAt = now;
        this.lastActivityAt = now;
        this.screenKey = screenKey;
        this.costAmount = BigDecimal.ZERO.setScale(6, RoundingMode.HALF_EVEN);
        this.referencesJson = "{}";
    }

    public boolean ownedBy(UUID actorId) {
        return userId.equals(actorId);
    }

    /** Registra uma pergunta respondida (ou recusada pelo modelo): consumo e última tela. */
    public void recordTurn(String screen, long input, long output, BigDecimal cost, String currency, Instant at) {
        Guard.require(input >= 0 && output >= 0 && cost != null && cost.signum() >= 0, "helpdesk.usage_invalid",
                "Consumo inválido");
        turns++;
        inputTokens += input;
        outputTokens += output;
        costAmount = costAmount.add(cost).setScale(6, RoundingMode.HALF_EVEN);
        if (currency != null) {
            costCurrency = currency;
        }
        if (screen != null) {
            screenKey = screen;
        }
        lastActivityAt = at;
    }

    public void touch(String screen, Instant at) {
        if (screen != null) {
            screenKey = screen;
        }
        lastActivityAt = at;
    }

    public void replaceReferences(String json) {
        this.referencesJson = Objects.requireNonNull(json, "json");
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public String getScreenKey() {
        return screenKey;
    }

    public int getTurns() {
        return turns;
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

    public String getReferencesJson() {
        return referencesJson;
    }
}
