package br.com.gestorcobranca.identity.domain.model;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Refresh token opaco e rotativo. Só o hash SHA-256 é armazenado. Cada uso gera um novo token e
 * marca o anterior como {@code USED}; apresentar um token já usado indica roubo (reuso) e
 * revoga a sessão inteira.
 */
@Entity
@Table(name = "refresh_token", schema = "identity")
public class RefreshToken extends TenantScopedEntity {

    public enum Status { ACTIVE, USED, REVOKED }

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected RefreshToken() {
    }

    public RefreshToken(UUID sessionId, String tokenHash, Instant expiresAt) {
        this.sessionId = sessionId;
        this.tokenHash = tokenHash;
        this.status = Status.ACTIVE;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return status == Status.ACTIVE && now.isBefore(expiresAt);
    }

    public void markUsed(Instant now) {
        this.status = Status.USED;
        this.usedAt = now;
    }

    public void revoke() {
        if (status == Status.ACTIVE) {
            this.status = Status.REVOKED;
        }
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
