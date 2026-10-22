package br.com.gestorcobranca.organization.domain.model;

import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Carteira: agrupamento de títulos de um credor, vinculado a um contrato, com vigência e
 * estratégia próprias. A atribuição à assessoria (RB02) é representada pelo status ACTIVE
 * dentro da vigência.
 */
@Entity
@Table(name = "portfolio", schema = "organization")
public class Portfolio extends TenantScopedEntity {

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "received_on", nullable = false)
    private LocalDate receivedOn;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PortfolioStatus status;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    protected Portfolio() {
    }

    public Portfolio(UUID creditorId, UUID contractId, String code, String name, String description,
                     LocalDate receivedOn, LocalDate validUntil) {
        Guard.require(creditorId != null, "portfolio.creditor_required", "Credor obrigatório");
        Guard.require(contractId != null, "portfolio.contract_required", "Contrato obrigatório");
        Guard.require(code != null && !code.isBlank(), "portfolio.code_required", "Código obrigatório");
        this.creditorId = creditorId;
        this.contractId = contractId;
        this.code = code.trim().toUpperCase();
        this.status = PortfolioStatus.DRAFT;
        update(name, description, receivedOn, validUntil);
    }

    public void update(String name, String description, LocalDate receivedOn, LocalDate validUntil) {
        Guard.require(name != null && !name.isBlank(), "portfolio.name_required", "Nome obrigatório");
        Guard.require(receivedOn != null, "portfolio.received_on_required", "Data de recebimento obrigatória");
        Guard.require(validUntil == null || !validUntil.isBefore(receivedOn), "portfolio.validity_invalid",
                "Vigência não pode terminar antes do recebimento");
        this.name = name.trim();
        this.description = description;
        this.receivedOn = receivedOn;
        this.validUntil = validUntil;
    }

    /**
     * Muda o status validando a transição.
     *
     * @return status anterior
     */
    public PortfolioStatus transitionTo(PortfolioStatus target, String reason) {
        if (!status.canTransitionTo(target)) {
            throw new ConflictException("portfolio.invalid_transition",
                    "Carteira não pode passar de %s para %s".formatted(status, target),
                    Map.of("from", status.name(), "to", target.name()));
        }
        if (target == PortfolioStatus.SUSPENDED || target == PortfolioStatus.RETURNED) {
            Guard.require(reason != null && !reason.isBlank(), "portfolio.reason_required",
                    "Informe o motivo da suspensão ou devolução");
        }
        PortfolioStatus previous = this.status;
        this.status = target;
        this.statusReason = reason;
        return previous;
    }

    /** Carteira operável na data: ativa e dentro da vigência (RB02). */
    public boolean isOperableOn(LocalDate date) {
        return status == PortfolioStatus.ACTIVE && (validUntil == null || !date.isAfter(validUntil));
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getContractId() {
        return contractId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getReceivedOn() {
        return receivedOn;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public PortfolioStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }
}
