package br.com.gestorcobranca.identity.domain.model;

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
 * Sessão autenticada de um usuário. O id da sessão vai no JWT ({@code sid}) e é validado a cada
 * requisição: revogar a sessão invalida o acesso imediatamente, sem esperar o token expirar.
 * <p>
 * Expiração por inatividade ({@code idleTimeout}) e absoluta ({@code absoluteExpiresAt}).
 */
@Entity
@Table(name = "auth_session", schema = "identity")
public class AuthSession extends TenantScopedEntity {

    public enum Status { ACTIVE, REVOKED, EXPIRED }

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "mfa_verified", nullable = false)
    private boolean mfaVerified;

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

    protected AuthSession() {
    }

    public AuthSession(UUID userId, boolean mfaVerified, Instant now, Duration maxLifetime, String clientIp, String userAgent) {
        this.userId = userId;
        this.status = Status.ACTIVE;
        this.mfaVerified = mfaVerified;
        this.startedAt = now;
        this.lastSeenAt = now;
        this.absoluteExpiresAt = now.plus(maxLifetime);
        this.clientIp = clientIp;
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

    /**
     * Fim do período de uso efetivo: a última atividade registrada, limitada pelo encerramento
     * (logout/revogação). Sessão expirada por inatividade não conta o tempo ocioso até a expiração.
     */
    public Instant activityEnd() {
        return endedAt != null && endedAt.isBefore(lastSeenAt) ? endedAt : lastSeenAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isMfaVerified() {
        return mfaVerified;
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

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getEndReason() {
        return endReason;
    }
}
