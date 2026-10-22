package br.com.gestorcobranca.identity.domain.model;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/** Hash de senha anterior, para impedir a reutilização das últimas N senhas. */
@Entity
@Table(name = "password_history", schema = "identity")
public class PasswordHistoryEntry extends TenantScopedEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "password_hash", nullable = false, updatable = false, length = 255)
    private String passwordHash;

    protected PasswordHistoryEntry() {
    }

    public PasswordHistoryEntry(UUID userId, String passwordHash) {
        this.userId = userId;
        this.passwordHash = passwordHash;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }
}
