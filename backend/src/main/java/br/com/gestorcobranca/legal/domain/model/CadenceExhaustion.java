package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Régua extrajudicial esgotada num caso de cobrança (critério de elegibilidade "régua
 * extrajudicial esgotada"): conhecida pelo evento {@code CadenceExhausted} do campaign, somente
 * para campanhas com finalidade {@code EXTRAJUDICIAL_COLLECTION}. Uma linha por inscrição,
 * imutável; o módulo legal não usa classes do campaign.
 */
@Entity
@Table(name = "cadence_exhaustion", schema = "legal")
public class CadenceExhaustion extends TenantScopedEntity {

    @Column(name = "enrollment_id", nullable = false, updatable = false)
    private UUID enrollmentId;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Column(name = "cadence_version", nullable = false, updatable = false)
    private int cadenceVersion;

    @Column(name = "collection_case_id", nullable = false, updatable = false)
    private UUID collectionCaseId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "steps_executed", nullable = false, updatable = false)
    private int stepsExecuted;

    @Column(name = "messages_sent", nullable = false, updatable = false)
    private int messagesSent;

    @Column(name = "exhausted_at", nullable = false, updatable = false)
    private Instant exhaustedAt;

    protected CadenceExhaustion() {
    }

    /** @param fact dados do evento de régua esgotada */
    public CadenceExhaustion(Fact fact) {
        Guard.require(fact.enrollmentId() != null && fact.campaignId() != null && fact.collectionCaseId() != null
                        && fact.creditorId() != null && fact.debtorId() != null && fact.exhaustedAt() != null,
                "cadence_exhaustion.incomplete", "Régua esgotada sem inscrição, campanha, caso, credor ou devedor");
        this.enrollmentId = fact.enrollmentId();
        this.campaignId = fact.campaignId();
        this.cadenceVersion = Math.max(1, fact.cadenceVersion());
        this.collectionCaseId = fact.collectionCaseId();
        this.creditorId = fact.creditorId();
        this.portfolioId = fact.portfolioId();
        this.debtorId = fact.debtorId();
        this.stepsExecuted = Math.max(0, fact.stepsExecuted());
        this.messagesSent = Math.max(0, fact.messagesSent());
        this.exhaustedAt = fact.exhaustedAt();
    }

    public record Fact(UUID enrollmentId, UUID campaignId, int cadenceVersion, UUID collectionCaseId, UUID creditorId,
                       UUID portfolioId, UUID debtorId, int stepsExecuted, int messagesSent, Instant exhaustedAt) {
    }

    public UUID getEnrollmentId() {
        return enrollmentId;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public int getCadenceVersion() {
        return cadenceVersion;
    }

    public UUID getCollectionCaseId() {
        return collectionCaseId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public int getStepsExecuted() {
        return stepsExecuted;
    }

    public int getMessagesSent() {
        return messagesSent;
    }

    public Instant getExhaustedAt() {
        return exhaustedAt;
    }
}
