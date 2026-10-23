package br.com.gestorcobranca.telephony.domain.model;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Intervalo encerrado de presença do operador (append-only no banco): base do tempo logado, das
 * pausas por motivo e da disponibilidade nos indicadores de produtividade.
 */
@Entity
@Table(name = "presence_interval", schema = "telephony")
public class PresenceInterval extends TenantScopedEntity {

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, updatable = false)
    private PresenceStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "pause_reason", length = 30, updatable = false)
    private PauseReason pauseReason;

    @Column(name = "campaign_id", updatable = false)
    private UUID campaignId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false, updatable = false)
    private Instant endedAt;

    @Column(name = "duration_seconds", nullable = false, updatable = false)
    private long durationSeconds;

    protected PresenceInterval() {
    }

    public PresenceInterval(Agent.PresenceChange change) {
        this.agentId = change.agentId();
        this.userId = change.userId();
        this.status = change.from();
        this.pauseReason = change.fromReason();
        this.campaignId = change.campaignId();
        this.startedAt = change.fromSince();
        this.endedAt = change.at();
        this.durationSeconds = change.durationSeconds();
    }

    public UUID getAgentId() {
        return agentId;
    }

    public UUID getUserId() {
        return userId;
    }

    public PresenceStatus getStatus() {
        return status;
    }

    public PauseReason getPauseReason() {
        return pauseReason;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }
}
