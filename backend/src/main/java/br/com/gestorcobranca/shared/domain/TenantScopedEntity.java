package br.com.gestorcobranca.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import java.util.UUID;

/**
 * Entidade pertencente a uma assessoria (tenant). O {@code tenant_id} é atribuído
 * automaticamente na persistência a partir do ator corrente e nunca muda. A tabela
 * correspondente deve ter RLS habilitado com {@code shared.enable_tenant_rls(...)}.
 */
@MappedSuperclass
public abstract class TenantScopedEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    protected TenantScopedEntity() {
        super();
    }

    public UUID getTenantId() {
        return tenantId;
    }

    void assignTenant(UUID tenantId) {
        if (this.tenantId != null && !this.tenantId.equals(tenantId)) {
            throw new IllegalStateException("Entidade %s pertence a outro tenant".formatted(getId()));
        }
        this.tenantId = tenantId;
    }
}
