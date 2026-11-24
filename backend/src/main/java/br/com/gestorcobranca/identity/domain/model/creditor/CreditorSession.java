package br.com.gestorcobranca.identity.domain.model.creditor;

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
 * Sessão da pessoa do credor. O id vai no token ({@code sid}) e é conferido no servidor a cada requisição, sem cache:
 * revogar vale na hora. Só nasce depois do MFA (a conta ativa sempre tem MFA). Expira por inatividade e por prazo
 * absoluto.
 */
@Entity
@Table(name = "creditor_session", schema = "identity")
public class CreditorSession extends TenantScopedEntity {

    public enum Status { ACTIVE, REVOKED, EXPIRED }

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "absolute_expires_at", nullable = false)
    private Instant absoluteExpiresAt;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "end_reason", length = 60)
    private String endReason;

    protected CreditorSession() {
    }

    public CreditorSession(UUID userId, UUID creditorId, Instant now, Duration maxLifetime, String clientIp,
                           String userAgent) {
        this.userId = userId;
        this.creditorId = creditorId;
        this.status = Status.ACTIVE;
        this.startedAt = now;
        this.lastSeenAt = now;
        this.absoluteExpiresAt = now.plus(maxLifetime);
        this.clientIp = clientIp == null || clientIp.length() <= 64 ? clientIp : clientIp.substring(0, 64);
        this.userAgent = userAgent == null || userAgent.length() <= 512 ? userAgent : userAgent.substring(0, 512);
    }

    public boolean isUsable(Instant now, Duration idleTimeout) {
        return status == Status.ACTIVE && now.isBefore(absoluteExpiresAt) && !lastSeenAt.plus(idleTimeout).isBefore(now);
    }

    public void touch(Instant now) {
        Guard.requireState(status == Status.ACTIVE, "session.inactive", "Sessão encerrada");
        this.lastSeenAt = now;
    }

    public void revoke(Instant now, String reason) {
        if (status == Status.ACTIVE) {
            this.status = Status.REVOKED;
            this.endedAt = now;
            this.endReason = reason;
        }
    }

    public void expire(Instant now) {
        if (status == Status.ACTIVE) {
            this.status = Status.EXPIRED;
            this.endedAt = now;
            this.endReason = "expired";
        }
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getAbsoluteExpiresAt() {
        return absoluteExpiresAt;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getUserAgent() {
        return userAgent;
    }
}
