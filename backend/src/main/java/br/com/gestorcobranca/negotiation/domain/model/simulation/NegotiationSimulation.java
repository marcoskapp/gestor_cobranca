package br.com.gestorcobranca.negotiation.domain.model.simulation;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Registro imutável de uma simulação (§2, RB03): quem simulou (tipo de ator, id e perfil de
 * alçada), com qual versão de política e sobreposição, data-base, nível pedido e concedido,
 * justificativa e a memória de cálculo completa (composição, envelope e ofertas, em JSON).
 * Append-only no banco. A parte 2 referencia a simulação na proposta para reproduzi-la.
 */
@Entity
@Table(name = "simulation", schema = "negotiation")
public class NegotiationSimulation extends TenantScopedEntity {

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;

    @Column(name = "policy_id", nullable = false, updatable = false)
    private UUID policyId;

    @Column(name = "policy_version", nullable = false, updatable = false)
    private int policyVersion;

    @Column(name = "override_code", updatable = false, length = 40)
    private String overrideCode;

    @Column(name = "reference_date", nullable = false, updatable = false)
    private LocalDate referenceDate;

    @Column(name = "actor_type", nullable = false, updatable = false, length = 20)
    private String actorType;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(name = "authority_profile_id", nullable = false, updatable = false)
    private UUID authorityProfileId;

    @Column(name = "authority_profile_code", nullable = false, updatable = false, length = 40)
    private String authorityProfileCode;

    @Column(name = "requested_level", nullable = false, updatable = false)
    private int requestedLevel;

    @Column(name = "granted_level", nullable = false, updatable = false)
    private int grantedLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "justification_reason", updatable = false, length = 30)
    private ConcessionReason justificationReason;

    @Column(name = "justification_note", updatable = false, length = 500)
    private String justificationNote;

    @Column(name = "title_count", nullable = false, updatable = false)
    private int titleCount;

    @Column(name = "max_days_overdue", nullable = false, updatable = false)
    private long maxDaysOverdue;

    @Column(name = "updated_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money updatedAmount;

    @Column(name = "best_offer_amount", updatable = false, precision = 19, scale = 2)
    private Money bestOfferAmount;

    @Column(name = "channel", updatable = false, length = 20)
    private String channel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calculation", nullable = false, updatable = false)
    private String calculation;

    @Column(name = "engine_version", nullable = false, updatable = false)
    private int engineVersion;

    protected NegotiationSimulation() {
    }

    /** Dados de identificação e resultado; {@code calculationJson} é a memória completa. */
    public record Header(UUID creditorId, UUID debtorId, UUID portfolioId, UUID policyId, int policyVersion,
                         String overrideCode, LocalDate referenceDate, String actorType, UUID actorId,
                         UUID authorityProfileId, String authorityProfileCode, int requestedLevel, int grantedLevel,
                         ConcessionReason justificationReason, String justificationNote, int titleCount,
                         long maxDaysOverdue, Money updatedAmount, Money bestOfferAmount, String channel) {
    }

    public NegotiationSimulation(Header h, String calculationJson, int engineVersion) {
        Guard.require(h.creditorId() != null && h.debtorId() != null && h.policyId() != null, "simulation.invalid",
                "Credor, devedor e política obrigatórios");
        Guard.require(h.requestedLevel() >= 0 && h.grantedLevel() >= 0, "simulation.invalid", "Níveis inválidos");
        Guard.require(h.justificationNote() == null || h.justificationNote().length() <= 500, "simulation.note_too_long",
                "Observação da justificativa até 500 caracteres");
        Guard.require(calculationJson != null && !calculationJson.isBlank(), "simulation.invalid", "Memória obrigatória");
        this.creditorId = h.creditorId();
        this.debtorId = h.debtorId();
        this.portfolioId = h.portfolioId();
        this.policyId = h.policyId();
        this.policyVersion = h.policyVersion();
        this.overrideCode = h.overrideCode();
        this.referenceDate = h.referenceDate();
        this.actorType = h.actorType();
        this.actorId = h.actorId();
        this.authorityProfileId = h.authorityProfileId();
        this.authorityProfileCode = h.authorityProfileCode();
        this.requestedLevel = h.requestedLevel();
        this.grantedLevel = h.grantedLevel();
        this.justificationReason = h.justificationReason();
        this.justificationNote = h.justificationNote();
        this.titleCount = h.titleCount();
        this.maxDaysOverdue = h.maxDaysOverdue();
        this.updatedAmount = h.updatedAmount();
        this.bestOfferAmount = h.bestOfferAmount();
        this.channel = h.channel();
        this.calculation = calculationJson;
        this.engineVersion = engineVersion;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public int getPolicyVersion() {
        return policyVersion;
    }

    public String getOverrideCode() {
        return overrideCode;
    }

    public LocalDate getReferenceDate() {
        return referenceDate;
    }

    public String getActorType() {
        return actorType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public UUID getAuthorityProfileId() {
        return authorityProfileId;
    }

    public String getAuthorityProfileCode() {
        return authorityProfileCode;
    }

    public int getRequestedLevel() {
        return requestedLevel;
    }

    public int getGrantedLevel() {
        return grantedLevel;
    }

    public ConcessionReason getJustificationReason() {
        return justificationReason;
    }

    public String getJustificationNote() {
        return justificationNote;
    }

    public int getTitleCount() {
        return titleCount;
    }

    public long getMaxDaysOverdue() {
        return maxDaysOverdue;
    }

    public Money getUpdatedAmount() {
        return updatedAmount;
    }

    public Money getBestOfferAmount() {
        return bestOfferAmount;
    }

    public String getChannel() {
        return channel;
    }

    public String getCalculation() {
        return calculation;
    }

    public int getEngineVersion() {
        return engineVersion;
    }
}
