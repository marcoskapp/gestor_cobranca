package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Acordo do módulo negotiation conhecido pelo jurídico, referenciado apenas por id e alimentado
 * pelo evento {@code AgreementStatusChanged} (o módulo legal não depende das classes do
 * negotiation): situação mais recente, se já foi quebrado (critério de elegibilidade "acordos
 * quebrados") e o caso jurídico vinculado, quando o acordo cobre títulos de um caso jurídico.
 */
@Entity
@Table(name = "agreement_record", schema = "legal")
public class AgreementRecord extends TenantScopedEntity {

    @Column(name = "agreement_id", nullable = false, updatable = false)
    private UUID agreementId;

    @Column(name = "collection_case_id")
    private UUID collectionCaseId;

    @Column(name = "debtor_id", nullable = false)
    private UUID debtorId;

    @Column(name = "creditor_id", nullable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id")
    private UUID portfolioId;

    @Column(name = "matter_id")
    private UUID matterId;

    @Column(name = "judicial", nullable = false)
    private boolean judicial;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "broken", nullable = false)
    private boolean broken;

    @Column(name = "replaced_agreement_id")
    private UUID replacedAgreementId;

    @Column(name = "last_event_at", nullable = false)
    private Instant lastEventAt;

    protected AgreementRecord() {
    }

    public AgreementRecord(UUID agreementId, UUID collectionCaseId, UUID debtorId, UUID creditorId, UUID portfolioId,
                           UUID replacedAgreementId) {
        this.agreementId = agreementId;
        this.collectionCaseId = collectionCaseId;
        this.debtorId = debtorId;
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.replacedAgreementId = replacedAgreementId;
        this.status = "UNKNOWN";
        this.lastEventAt = Instant.EPOCH;
    }

    /**
     * Aplica o evento se não for mais antigo que o último aplicado (entrega fora de ordem).
     *
     * @return true se aplicado
     */
    public boolean apply(String newStatus, Instant occurredAt) {
        if (occurredAt.isBefore(lastEventAt)) {
            if ("BROKEN".equals(newStatus)) {
                this.broken = true;
            }
            return false;
        }
        this.status = newStatus;
        this.lastEventAt = occurredAt;
        if ("BROKEN".equals(newStatus)) {
            this.broken = true;
        }
        return true;
    }

    public void linkMatter(UUID matterId, boolean judicial) {
        this.matterId = matterId;
        this.judicial = judicial;
    }

    public UUID getAgreementId() {
        return agreementId;
    }

    public UUID getCollectionCaseId() {
        return collectionCaseId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public UUID getMatterId() {
        return matterId;
    }

    public boolean isJudicial() {
        return judicial;
    }

    public String getStatus() {
        return status;
    }

    public boolean isBroken() {
        return broken;
    }

    public UUID getReplacedAgreementId() {
        return replacedAgreementId;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }
}
