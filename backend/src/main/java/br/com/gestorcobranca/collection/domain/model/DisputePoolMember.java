package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Analista que recebe contestações na distribuição automática, para um credor ou para todos (credor nulo). Credor
 * com analistas próprios usa só eles; os demais credores usam os gerais.
 */
@Entity
@Table(name = "dispute_pool_member", schema = "collection")
public class DisputePoolMember extends TenantScopedEntity {

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected DisputePoolMember() {
    }

    public DisputePoolMember(UUID creditorId, UUID userId) {
        Guard.require(userId != null, "dispute_pool.user_required", "Analista obrigatório");
        this.creditorId = creditorId;
        this.userId = userId;
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isActive() {
        return active;
    }
}
