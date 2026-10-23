package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Linha do tempo das fases do caso jurídico (append-only no banco). */
@Entity
@Table(name = "matter_stage_history", schema = "legal")
public class StageHistoryEntry extends TenantScopedEntity {

    @Column(name = "matter_id", nullable = false, updatable = false)
    private UUID matterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_stage", length = 30, updatable = false)
    private LegalStage fromStage;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_stage", nullable = false, length = 30, updatable = false)
    private LegalStage toStage;

    @Column(name = "reason", length = LegalMatter.REASON_MAX, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "closure_reason", length = 30, updatable = false)
    private ClosureReason closureReason;

    @Column(name = "cause", nullable = false, length = 40, updatable = false)
    private String trigger;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected StageHistoryEntry() {
    }

    /** @param trigger {@code manual}, {@code lawsuit}, {@code movement}, {@code agreement}, {@code referral}… */
    public StageHistoryEntry(UUID matterId, LegalStage fromStage, LegalStage toStage, String reason,
                             ClosureReason closureReason, String trigger, Instant occurredAt) {
        this.matterId = matterId;
        this.fromStage = fromStage;
        this.toStage = toStage;
        this.reason = reason;
        this.closureReason = closureReason;
        this.trigger = trigger;
        this.occurredAt = occurredAt;
    }

    public UUID getMatterId() {
        return matterId;
    }

    public LegalStage getFromStage() {
        return fromStage;
    }

    public LegalStage getToStage() {
        return toStage;
    }

    public String getReason() {
        return reason;
    }

    public ClosureReason getClosureReason() {
        return closureReason;
    }

    public String getTrigger() {
        return trigger;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
