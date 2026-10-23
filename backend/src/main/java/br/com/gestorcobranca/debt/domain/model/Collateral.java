package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Garantia de um contrato de origem. Cobre todos os títulos do contrato ou, quando o contrato
 * tem garantias por parcela, apenas os títulos vinculados ({@link #getTitleIds()}).
 * <p>
 * O valor recuperável estimado é a avaliação mais recente ({@link CollateralAppraisal},
 * histórico append-only), mantida em cache na mesma transação de cada avaliação, como o saldo
 * do título em relação ao razão. Mudanças de situação seguem {@link CollateralStatus}.
 */
@Entity
@Table(name = "collateral", schema = "debt")
public class Collateral extends TenantScopedEntity {

    private static final int DESCRIPTION_MAX = 1000;
    private static final int REASON_MAX = 500;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "origin_contract_id", nullable = false, updatable = false)
    private UUID originContractId;

    @Column(name = "external_id", length = 80, updatable = false)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40, updatable = false)
    private CollateralType type;

    @Column(name = "description", length = DESCRIPTION_MAX)
    private String description;

    @Embedded
    private AssetIdentification asset;

    @Column(name = "registration_authority", length = 150)
    private String registrationAuthority;

    @Column(name = "registration_number", length = 60)
    private String registrationNumber;

    @Column(name = "registration_date")
    private LocalDate registrationDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CollateralStatus status;

    @Column(name = "status_reason", length = REASON_MAX)
    private String statusReason;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Column(name = "estimated_value", precision = 19, scale = 2)
    private Money estimatedValue;

    @Column(name = "last_appraisal_date")
    private LocalDate lastAppraisalDate;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "collateral_title", schema = "debt", joinColumns = @JoinColumn(name = "collateral_id"))
    @Column(name = "title_id", nullable = false)
    private Set<UUID> titleIds = new HashSet<>();

    protected Collateral() {
    }

    /**
     * @param externalId identificador da garantia no credor (opcional; obrigatório para outros bens em cargas)
     */
    public Collateral(UUID creditorId, UUID originContractId, String externalId, CollateralType type,
                      String description, AssetIdentification asset) {
        Guard.require(creditorId != null, "collateral.creditor_required", "Credor obrigatório");
        Guard.require(originContractId != null, "collateral.contract_required", "Contrato de origem obrigatório");
        Guard.require(type != null, "collateral.type_required", "Tipo de garantia obrigatório");
        this.creditorId = creditorId;
        this.originContractId = originContractId;
        this.externalId = externalId == null || externalId.isBlank() ? null : DebtTitle.validExternalId(externalId, "collateral");
        this.type = type;
        this.status = CollateralStatus.ACTIVE;
        describe(description, asset);
    }

    /**
     * Altera descrição e identificação do bem. A natureza do bem deve ser compatível com o tipo
     * da garantia; outros bens exigem descrição.
     *
     * @return true se algo mudou
     */
    public boolean describe(String description, AssetIdentification asset) {
        Guard.require(asset != null, "collateral.asset_required", "Identificação do bem obrigatória");
        Guard.require(type.allowedAssetKinds().contains(asset.getKind()), "collateral.asset_kind_mismatch",
                "Bem do tipo %s não é admitido em garantia %s".formatted(asset.getKind(), type));
        String newDescription = description == null || description.isBlank() ? null : description.trim();
        Guard.require(newDescription == null || newDescription.length() <= DESCRIPTION_MAX, "collateral.description_too_long",
                "Descrição deve ter até %d caracteres".formatted(DESCRIPTION_MAX));
        Guard.require(asset.getKind() != AssetKind.OTHER || newDescription != null, "collateral.description_required",
                "Descreva o bem dado em garantia");
        boolean changed = !Objects.equals(newDescription, this.description) || !asset.sameAs(this.asset);
        this.description = newDescription;
        this.asset = asset;
        return changed;
    }

    /**
     * Registro do gravame (órgão/cartório, número do registro ou do gravame, data). Campos nulos
     * significam gravame ainda não registrado.
     *
     * @return true se algo mudou
     */
    public boolean registerLien(String authority, String number, LocalDate date) {
        String newAuthority = text(authority, 150, "collateral.registration_authority_too_long", "Órgão/cartório");
        String newNumber = text(number, 60, "collateral.registration_number_too_long", "Número do registro");
        Guard.require(date == null || newAuthority != null || newNumber != null, "collateral.registration_incomplete",
                "Informe o órgão ou o número do registro junto com a data");
        boolean changed = !Objects.equals(newAuthority, registrationAuthority) || !Objects.equals(newNumber, registrationNumber)
                || !Objects.equals(date, registrationDate);
        this.registrationAuthority = newAuthority;
        this.registrationNumber = newNumber;
        this.registrationDate = date;
        return changed;
    }

    /**
     * Restringe a garantia aos títulos informados (que o caso de uso valida pertencerem ao
     * contrato). Conjunto vazio: a garantia cobre todos os títulos do contrato.
     *
     * @return true se o vínculo mudou
     */
    public boolean coverTitles(Collection<UUID> titles) {
        Set<UUID> requested = titles == null ? Set.of() : Set.copyOf(titles);
        if (requested.equals(titleIds)) {
            return false;
        }
        titleIds.clear();
        titleIds.addAll(requested);
        return true;
    }

    /** @return situação anterior */
    public CollateralStatus changeStatus(CollateralStatus target, String reason, Instant at) {
        Guard.require(target != null, "collateral.status_required", "Situação obrigatória");
        Guard.require(reason != null && !reason.isBlank(), "collateral.reason_required", "Informe o motivo");
        Guard.require(reason.length() <= REASON_MAX, "collateral.reason_too_long", "Motivo muito longo");
        // consolidação da propriedade é procedimento da alienação fiduciária de imóvel (Lei 9.514/1997)
        Guard.require(target != CollateralStatus.CONSOLIDATED || type == CollateralType.REAL_ESTATE_FIDUCIARY_ALIENATION,
                "collateral.status_not_applicable", "Consolidação só se aplica a alienação fiduciária de imóvel");
        if (!status.canTransitionTo(target)) {
            throw new ConflictException("collateral.invalid_transition",
                    "Garantia não pode passar de %s para %s".formatted(status, target),
                    Map.of("collateralId", getId().toString(), "from", status.name(), "to", target.name()));
        }
        CollateralStatus previous = status;
        this.status = target;
        this.statusReason = reason.trim();
        this.statusChangedAt = Objects.requireNonNull(at, "at");
        return previous;
    }

    /**
     * Atualiza o valor recuperável estimado se a avaliação for a mais recente (por data; no
     * mesmo dia, a última registrada prevalece).
     *
     * @return true se a estimativa mudou de avaliação
     */
    public boolean recordAppraisal(CollateralAppraisal appraisal) {
        Guard.require(appraisal.getCollateralId().equals(getId()), "collateral.appraisal_mismatch",
                "Avaliação de outra garantia");
        if (lastAppraisalDate != null && appraisal.getAppraisalDate().isBefore(lastAppraisalDate)) {
            return false;
        }
        this.estimatedValue = appraisal.getAmount();
        this.lastAppraisalDate = appraisal.getAppraisalDate();
        return true;
    }

    /** Cobre o título (todos os títulos do contrato quando não há vínculo específico). */
    public boolean covers(UUID titleId, UUID titleContractId) {
        return originContractId.equals(titleContractId) && (titleIds.isEmpty() || titleIds.contains(titleId));
    }

    private static String text(String value, int max, String code, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        Guard.require(trimmed.length() <= max, code, "%s deve ter até %d caracteres".formatted(label, max));
        return trimmed;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getOriginContractId() {
        return originContractId;
    }

    public String getExternalId() {
        return externalId;
    }

    public CollateralType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public AssetIdentification getAsset() {
        return asset;
    }

    public String getRegistrationAuthority() {
        return registrationAuthority;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public LocalDate getRegistrationDate() {
        return registrationDate;
    }

    public CollateralStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Instant getStatusChangedAt() {
        return statusChangedAt;
    }

    /** Valor recuperável estimado (última avaliação); nulo sem avaliação. */
    public Money getEstimatedValue() {
        return estimatedValue;
    }

    public LocalDate getLastAppraisalDate() {
        return lastAppraisalDate;
    }

    public Set<UUID> getTitleIds() {
        return Set.copyOf(titleIds);
    }
}
