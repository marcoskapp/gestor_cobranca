package br.com.gestorcobranca.incentive.domain.model.attribution;

import br.com.gestorcobranca.shared.domain.Percentage;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * Crédito resolvido para um beneficiário: peso da origem (§3.2) e fator de renegociação (§3.4)
 * definidos pelo plano vigente na formalização.
 *
 * @see br.com.gestorcobranca.incentive.domain.service.CreditPolicy
 */
@Embeddable
public class AttributionCredit {

    @Enumerated(EnumType.STRING)
    @Column(name = "beneficiary_type", nullable = false, length = 10)
    private BeneficiaryType beneficiaryType;

    @Column(name = "beneficiary_id", nullable = false)
    private UUID beneficiaryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20)
    private ParticipantRole role;

    @Column(name = "weight", nullable = false, precision = 9, scale = 4)
    private Percentage weight;

    @Column(name = "renegotiation_factor", nullable = false, precision = 9, scale = 4)
    private Percentage renegotiationFactor;

    @Column(name = "reason", nullable = false, length = 60)
    private String reason;

    protected AttributionCredit() {
    }

    public AttributionCredit(BeneficiaryType beneficiaryType, UUID beneficiaryId, ParticipantRole role,
                             Percentage weight, Percentage renegotiationFactor, String reason) {
        this.beneficiaryType = Objects.requireNonNull(beneficiaryType, "beneficiaryType");
        this.beneficiaryId = Objects.requireNonNull(beneficiaryId, "beneficiaryId");
        this.role = role;
        this.weight = Objects.requireNonNull(weight, "weight");
        this.renegotiationFactor = Objects.requireNonNull(renegotiationFactor, "renegotiationFactor");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** Fração efetiva do crédito (peso × fator de renegociação), ex.: 30% × 50% = 0,15. */
    public BigDecimal effectiveFraction() {
        return weight.asFraction().multiply(renegotiationFactor.asFraction()).setScale(10, RoundingMode.HALF_EVEN);
    }

    public BeneficiaryType getBeneficiaryType() {
        return beneficiaryType;
    }

    public UUID getBeneficiaryId() {
        return beneficiaryId;
    }

    public ParticipantRole getRole() {
        return role;
    }

    public Percentage getWeight() {
        return weight;
    }

    public Percentage getRenegotiationFactor() {
        return renegotiationFactor;
    }

    public String getReason() {
        return reason;
    }
}
