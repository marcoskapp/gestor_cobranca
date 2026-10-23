package br.com.gestorcobranca.negotiation.domain.model.agreement;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Título coberto pelo acordo. Enquanto o acordo está em vigor o vínculo é {@code active}; um índice
 * único parcial no banco impede que o mesmo título esteja em dois acordos em vigor (defesa contra
 * formalizações concorrentes).
 */
@Entity
@Table(name = "agreement_title", schema = "negotiation")
public class AgreementTitle extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agreement_id", nullable = false, updatable = false)
    private Agreement agreement;

    @Column(name = "title_id", nullable = false, updatable = false)
    private UUID titleId;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected AgreementTitle() {
    }

    AgreementTitle(Agreement agreement, UUID titleId) {
        this.agreement = agreement;
        this.titleId = titleId;
        this.active = true;
    }

    void release() {
        active = false;
    }

    void reactivate() {
        active = true;
    }

    public UUID getTitleId() {
        return titleId;
    }

    public boolean isActive() {
        return active;
    }
}
