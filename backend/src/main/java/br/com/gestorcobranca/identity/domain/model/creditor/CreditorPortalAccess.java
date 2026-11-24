package br.com.gestorcobranca.identity.domain.model.creditor;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Portal do credor liberado (ou não) para um credor: sem liberação, nenhum convite e nenhuma sessão vale. */
@Entity
@Table(name = "creditor_portal_access", schema = "identity")
public class CreditorPortalAccess extends TenantScopedEntity {

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "enabled_at")
    private Instant enabledAt;

    @Column(name = "enabled_by")
    private UUID enabledBy;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "disabled_by")
    private UUID disabledBy;

    protected CreditorPortalAccess() {
    }

    public CreditorPortalAccess(UUID creditorId) {
        this.creditorId = creditorId;
    }

    /** @return true se mudou */
    public boolean enable(UUID by, Instant now) {
        if (enabled) {
            return false;
        }
        this.enabled = true;
        this.enabledAt = now;
        this.enabledBy = by;
        return true;
    }

    /** @return true se mudou */
    public boolean disable(UUID by, Instant now) {
        if (!enabled) {
            return false;
        }
        this.enabled = false;
        this.disabledAt = now;
        this.disabledBy = by;
        return true;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getEnabledAt() {
        return enabledAt;
    }
}
