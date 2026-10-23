package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Andamento processual (append-only no banco): data, natureza, descrição (criptografada — o teor
 * de decisões e publicações cita as partes), origem (manual ou provedor, com identificador externo
 * para deduplicar capturas), valor (avaliação, arrematação, levantamento) e garantia afetada.
 */
@Entity
@Table(name = "case_movement", schema = "legal")
public class CaseMovement extends TenantScopedEntity {

    public static final int DESCRIPTION_MAX = 6000;

    @Column(name = "lawsuit_id", nullable = false, updatable = false)
    private UUID lawsuitId;

    @Column(name = "matter_id", nullable = false, updatable = false)
    private UUID matterId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "occurred_on", nullable = false, updatable = false)
    private LocalDate occurredOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30, updatable = false)
    private MovementType type;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "description_encrypted", length = 24000, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10, updatable = false)
    private MovementSource source;

    @Column(name = "provider", length = 40, updatable = false)
    private String provider;

    @Column(name = "external_id", length = 120, updatable = false)
    private String externalId;

    @Column(name = "amount", precision = 19, scale = 2, updatable = false)
    private Money amount;

    @Column(name = "collateral_id", updatable = false)
    private UUID collateralId;

    protected CaseMovement() {
    }

    public CaseMovement(UUID lawsuitId, UUID matterId, UUID creditorId, LocalDate occurredOn, MovementType type,
                        String description, MovementSource source, String provider, String externalId, Money amount,
                        UUID collateralId, LocalDate today, LocalDate filedOn) {
        Guard.require(type != null, "legal_movement.type_required", "Natureza do andamento obrigatória");
        Guard.require(occurredOn != null && !occurredOn.isAfter(today), "legal_movement.date_invalid",
                "Data do andamento obrigatória e não futura");
        Guard.require(type == MovementType.DISTRIBUTION || !occurredOn.isBefore(filedOn), "legal_movement.date_before_filing",
                "Andamento anterior à distribuição do processo");
        String text = description == null || description.isBlank() ? null : description.trim();
        Guard.require(text == null || text.length() <= DESCRIPTION_MAX, "legal_movement.description_too_long",
                "Descrição deve ter até %d caracteres".formatted(DESCRIPTION_MAX));
        Guard.require(type != MovementType.OTHER || text != null, "legal_movement.description_required",
                "Descreva o andamento");
        Guard.require(amount == null || amount.isPositive(), "legal_movement.amount_invalid", "Valor deve ser positivo");
        Guard.require(amount == null || type.carriesAmount(), "legal_movement.amount_not_allowed",
                "Valor só se aplica a avaliação, arrematação, adjudicação e levantamento");
        Guard.require(type != MovementType.FUNDS_RELEASED || amount != null, "legal_movement.amount_required",
                "Informe o valor levantado");
        Guard.require(source != MovementSource.PROVIDER || (externalId != null && provider != null),
                "legal_movement.external_id_required", "Andamento capturado exige provedor e identificador externo");
        this.lawsuitId = lawsuitId;
        this.matterId = matterId;
        this.creditorId = creditorId;
        this.occurredOn = occurredOn;
        this.type = type;
        this.description = text;
        this.source = source;
        this.provider = provider;
        this.externalId = externalId;
        this.amount = amount;
        this.collateralId = collateralId;
    }

    public UUID getLawsuitId() {
        return lawsuitId;
    }

    public UUID getMatterId() {
        return matterId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public LocalDate getOccurredOn() {
        return occurredOn;
    }

    public MovementType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public MovementSource getSource() {
        return source;
    }

    public String getProvider() {
        return provider;
    }

    public String getExternalId() {
        return externalId;
    }

    public Money getAmount() {
        return amount;
    }

    public UUID getCollateralId() {
        return collateralId;
    }
}
