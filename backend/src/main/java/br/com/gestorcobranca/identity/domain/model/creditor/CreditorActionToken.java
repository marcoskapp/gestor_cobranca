package br.com.gestorcobranca.identity.domain.model.creditor;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Link de uso único enviado por e-mail à pessoa do credor: convite (ativação com senha e MFA) ou redefinição de senha.
 * Só o hash; emitir um novo invalida os anteriores do mesmo propósito; revogar a conta invalida todos.
 */
@Entity
@Table(name = "creditor_action_token", schema = "identity")
public class CreditorActionToken extends TenantScopedEntity {

    public enum Purpose { INVITATION, PASSWORD_RESET }

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, updatable = false, length = 20)
    private Purpose purpose;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "invalidated", nullable = false)
    private boolean invalidated;

    protected CreditorActionToken() {
    }

    public CreditorActionToken(UUID userId, Purpose purpose, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.purpose = purpose;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && !invalidated && now.isBefore(expiresAt);
    }

    public void markUsed(Instant now) {
        this.usedAt = now;
    }

    public void invalidate() {
        this.invalidated = true;
    }

    public UUID getUserId() {
        return userId;
    }

    public Purpose getPurpose() {
        return purpose;
    }
}
