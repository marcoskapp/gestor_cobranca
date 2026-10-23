package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Coobrigado de um contrato de origem (avalista, fiador, codevedor, terceiro garantidor). A
 * pessoa é o mesmo cadastro de devedor, consolidado pelo documento (RB01). O devedor principal
 * continua no contrato e nos títulos; aqui ficam somente as demais partes.
 * <p>
 * A participação pode ser encerrada (ex.: exoneração da fiança); o registro permanece.
 */
@Entity
@Table(name = "obligation_party", schema = "debt")
public class ObligationParty extends TenantScopedEntity {

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "origin_contract_id", nullable = false, updatable = false)
    private UUID originContractId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 40, updatable = false)
    private ObligationRole role;

    @Column(name = "since_date")
    private LocalDate sinceDate;

    @Column(name = "ended_on")
    private LocalDate endedOn;

    @Column(name = "end_reason", length = 500)
    private String endReason;

    protected ObligationParty() {
    }

    /**
     * @param principalDebtorId devedor principal do contrato, que não pode ser coobrigado do próprio contrato
     */
    public ObligationParty(UUID creditorId, UUID originContractId, UUID principalDebtorId, UUID debtorId,
                           ObligationRole role, LocalDate sinceDate) {
        Guard.require(creditorId != null, "obligation_party.creditor_required", "Credor obrigatório");
        Guard.require(originContractId != null, "obligation_party.contract_required", "Contrato de origem obrigatório");
        Guard.require(debtorId != null, "obligation_party.debtor_required", "Pessoa obrigatória");
        Guard.require(role != null, "obligation_party.role_required", "Papel obrigatório");
        Guard.require(role != ObligationRole.PRINCIPAL, "obligation_party.principal_not_allowed",
                "O devedor principal é o do contrato; cadastre aqui apenas coobrigados");
        Guard.require(!debtorId.equals(principalDebtorId), "obligation_party.principal_cannot_be_party",
                "O devedor principal não pode ser coobrigado do próprio contrato");
        this.creditorId = creditorId;
        this.originContractId = originContractId;
        this.debtorId = debtorId;
        this.role = role;
        this.sinceDate = sinceDate;
    }

    /** @return true se a data mudou */
    public boolean updateSince(LocalDate sinceDate) {
        requireActive();
        boolean changed = !Objects.equals(this.sinceDate, sinceDate);
        this.sinceDate = sinceDate;
        return changed;
    }

    public void end(LocalDate on, String reason) {
        requireActive();
        Guard.require(on != null, "obligation_party.end_date_required", "Data de encerramento obrigatória");
        Guard.require(sinceDate == null || !on.isBefore(sinceDate), "obligation_party.end_before_start",
                "Encerramento anterior ao início da participação");
        Guard.require(reason != null && !reason.isBlank(), "obligation_party.reason_required", "Informe o motivo");
        Guard.require(reason.length() <= 500, "obligation_party.reason_too_long", "Motivo muito longo");
        this.endedOn = on;
        this.endReason = reason.trim();
    }

    public boolean isActive() {
        return endedOn == null;
    }

    private void requireActive() {
        Guard.requireState(isActive(), "obligation_party.ended", "Participação já encerrada");
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getOriginContractId() {
        return originContractId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public ObligationRole getRole() {
        return role;
    }

    public LocalDate getSinceDate() {
        return sinceDate;
    }

    public LocalDate getEndedOn() {
        return endedOn;
    }

    public String getEndReason() {
        return endReason;
    }
}
