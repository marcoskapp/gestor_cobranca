package br.com.gestorcobranca.assistant.domain.model;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Fato de acordo para as métricas de qualidade (acordos do assistente × humanos, quebra precoce):
 * origem da atribuição registrada pelo negotiation e situação atual, alimentados pelos eventos
 * {@code AgreementAttributionRecorded} e {@code AgreementStatusChanged} (que podem chegar em
 * qualquer ordem). Somente ids, valores e datas.
 */
@Entity
@Table(name = "agreement_fact", schema = "assistant")
public class AssistantAgreementFact extends TenantScopedEntity {

    @Column(name = "agreement_id", nullable = false, updatable = false)
    private UUID agreementId;

    @Column(name = "creditor_id", nullable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id")
    private UUID portfolioId;

    @Column(name = "case_id")
    private UUID caseId;

    @Column(name = "origin", length = 40)
    private String origin;

    @Column(name = "assistant_policy_id")
    private UUID assistantPolicyId;

    @Column(name = "amount", precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "formalized_at")
    private Instant formalizedAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "broken_at")
    private Instant brokenAt;

    @Column(name = "status_at", nullable = false)
    private Instant statusAt;

    protected AssistantAgreementFact() {
    }

    public AssistantAgreementFact(UUID agreementId, UUID creditorId, UUID portfolioId, UUID caseId, String status, Instant at) {
        this.agreementId = agreementId;
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.caseId = caseId;
        this.status = status;
        this.statusAt = at;
    }

    public void attribution(String origin, UUID assistantPolicyId, BigDecimal amount, Instant formalizedAt) {
        this.origin = origin;
        this.assistantPolicyId = assistantPolicyId;
        this.amount = amount;
        this.formalizedAt = formalizedAt;
    }

    /** Situação mais recente (eventos fora de ordem não regridem). */
    public void status(String newStatus, BigDecimal totalAmount, Instant at) {
        if ("FORMALIZED".equals(newStatus) && formalizedAt == null) {
            this.formalizedAt = at;
        }
        if (amount == null && totalAmount != null) {
            this.amount = totalAmount;
        }
        if ("BROKEN".equals(newStatus) && brokenAt == null) {
            this.brokenAt = at;
        }
        boolean regression = "FORMALIZED".equals(newStatus) && !"FORMALIZED".equals(status);
        if (!regression && !at.isBefore(statusAt)) {
            this.status = newStatus;
            this.statusAt = at;
        }
    }

    public UUID getAgreementId() {
        return agreementId;
    }

    public String getOrigin() {
        return origin;
    }

    public String getStatus() {
        return status;
    }

    public Instant getBrokenAt() {
        return brokenAt;
    }

    public Instant getFormalizedAt() {
        return formalizedAt;
    }

    public UUID getAssistantPolicyId() {
        return assistantPolicyId;
    }
}
