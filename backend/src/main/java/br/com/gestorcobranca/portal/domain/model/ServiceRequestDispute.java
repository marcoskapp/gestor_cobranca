package br.com.gestorcobranca.portal.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Registro, no collection, da contestação feita pelo devedor no portal (I14): {@code PENDING} até o job
 * {@code portal.dispute_intake} rodar; depois o resultado (OPENED, ALREADY_OPEN, NO_CASE ou NO_TITLES). Linha própria,
 * separada do pedido, para o job da contestação e o do atendimento humano não concorrerem pela mesma linha.
 */
@Entity
@Table(name = "service_request_dispute", schema = "portal")
public class ServiceRequestDispute extends TenantScopedEntity {

    public static final String PENDING = "PENDING";
    static final Set<String> OUTCOMES = Set.of("OPENED", "ALREADY_OPEN", "NO_CASE", "NO_TITLES");

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "status", nullable = false, length = 15)
    private String status;

    @Column(name = "registered_at")
    private Instant registeredAt;

    @Column(name = "disputes_opened")
    private Integer disputesOpened;

    protected ServiceRequestDispute() {
    }

    public ServiceRequestDispute(UUID requestId) {
        this.requestId = Guard.requireNonNull(requestId, "portal.request_required", "Pedido obrigatório");
        this.status = PENDING;
    }

    public void registered(String outcome, int opened, Instant now) {
        Guard.require(OUTCOMES.contains(outcome), "portal.dispute_outcome_invalid", "Resultado inválido");
        this.status = outcome;
        this.disputesOpened = opened;
        this.registeredAt = now;
    }

    public boolean isPending() {
        return PENDING.equals(status);
    }

    public UUID getRequestId() { return requestId; }
    public String getStatus() { return status; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Integer getDisputesOpened() { return disputesOpened; }
}
