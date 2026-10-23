package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.legal.domain.service.ViabilityCalculator;
import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Percentage;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Análise de viabilidade registrada (append-only): fatos considerados (dívida, garantias e
 * avaliações, prescrição — fotografias em JSON), premissas (outros bens, taxa de realização,
 * probabilidade de êxito, custas previstas), resultado do {@link ViabilityCalculator}, checklist
 * documental, conclusão (viável ou não), vias recomendadas e parecer (criptografado). Uma nova
 * análise substitui a anterior como vigente; nenhuma é alterada.
 */
@Entity
@Table(name = "viability_assessment", schema = "legal")
public class ViabilityAssessment extends TenantScopedEntity {

    public static final int OPINION_MAX = 8000;

    @Column(name = "matter_id", nullable = false, updatable = false)
    private UUID matterId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "debt_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money debtAmount;

    @Column(name = "collectible_debt", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money collectibleDebt;

    @Column(name = "collateral_value", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money collateralValue;

    @Column(name = "collateral_recoverable", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money collateralRecoverable;

    @Column(name = "other_assets_value", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money otherAssetsValue;

    @Column(name = "realization_rate", nullable = false, precision = 9, scale = 4, updatable = false)
    private Percentage realizationRate;

    @Column(name = "success_probability", nullable = false, precision = 9, scale = 4, updatable = false)
    private Percentage successProbability;

    @Column(name = "expected_costs", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money expectedCosts;

    @Column(name = "estimated_recoverable", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money estimatedRecoverable;

    @Column(name = "net_benefit", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money netBenefit;

    @Column(name = "cost_benefit_ratio", precision = 19, scale = 2, updatable = false)
    private BigDecimal costBenefitRatio;

    @Column(name = "economically_viable", nullable = false, updatable = false)
    private boolean economicallyViable;

    @Column(name = "viable", nullable = false, updatable = false)
    private boolean viable;

    /** Vias recomendadas na ordem de preferência, separadas por vírgula (a primeira é a principal). */
    @Column(name = "recommended_routes", length = 300, updatable = false)
    private String recommendedRoutes;

    @Column(name = "available_routes", length = 300, updatable = false)
    private String availableRoutes;

    @Column(name = "suggested_routes", length = 300, updatable = false)
    private String suggestedRoutes;

    @Column(name = "flags", length = 500, updatable = false)
    private String flags;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "viability_checklist_item", schema = "legal",
            joinColumns = @JoinColumn(name = "assessment_id"))
    @OrderColumn(name = "position")
    private List<ChecklistItem> checklist = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "collateral_snapshot", nullable = false, updatable = false)
    private String collateralSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prescription_snapshot", nullable = false, updatable = false)
    private String prescriptionSnapshot;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "asset_search_encrypted", length = 24000, updatable = false)
    private String assetSearchNotes;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "opinion_encrypted", length = 32000, updatable = false)
    private String opinion;

    protected ViabilityAssessment() {
    }

    /**
     * Valida a conclusão contra o cálculo: vias recomendadas precisam estar disponíveis; a
     * conclusão favorável exige ao menos uma via e checklist sem item obrigatório faltante; a
     * conclusão favorável com custo × benefício negativo e a inviabilidade exigem parecer.
     */
    public ViabilityAssessment(UUID matterId, UUID creditorId, ViabilityCalculator.Input input,
                               ViabilityCalculator.Result result, boolean viable, List<LegalRoute> recommended,
                               List<ChecklistItem> checklist, String assetSearchNotes, String opinion,
                               String collateralSnapshot, String prescriptionSnapshot) {
        List<LegalRoute> routes = recommended == null ? List.of() : List.copyOf(new LinkedHashSet<>(recommended));
        String parecer = opinion == null || opinion.isBlank() ? null : opinion.trim();
        Guard.require(parecer == null || parecer.length() <= OPINION_MAX, "legal_viability.opinion_too_long",
                "Parecer deve ter até %d caracteres".formatted(OPINION_MAX));
        Guard.require(assetSearchNotes == null || assetSearchNotes.length() <= 6000, "legal_viability.asset_notes_too_long",
                "Pesquisa patrimonial deve ter até 6000 caracteres");
        List<ChecklistItem> items = checklist == null ? List.of() : List.copyOf(checklist);
        Guard.require(items.stream().map(ChecklistItem::getCode).distinct().count() == items.size(),
                "legal_viability.checklist_duplicate", "Itens do checklist repetidos");
        if (viable) {
            Guard.require(!routes.isEmpty(), "legal_viability.route_required", "Informe a via recomendada");
            List<LegalRoute> unavailable = routes.stream().filter(r -> !result.availableRoutes().contains(r)).toList();
            if (!unavailable.isEmpty()) {
                throw new BusinessRuleException("legal_viability.route_unavailable",
                        "Via indisponível para os títulos e garantias do caso: " + unavailable);
            }
            Guard.require(items.stream().noneMatch(ChecklistItem::blocking), "legal_viability.checklist_incomplete",
                    "Há documentos obrigatórios faltantes no checklist");
            Guard.require(result.economicallyViable() || parecer != null, "legal_viability.opinion_required",
                    "Conclusão favorável com custo × benefício negativo exige parecer");
        } else {
            Guard.require(routes.isEmpty(), "legal_viability.route_not_allowed",
                    "Conclusão pela inviabilidade não leva vias recomendadas");
            Guard.require(parecer != null, "legal_viability.opinion_required", "Conclusão pela inviabilidade exige parecer");
        }
        this.matterId = matterId;
        this.creditorId = creditorId;
        this.debtAmount = result.debtAmount();
        this.collectibleDebt = result.collectibleDebt();
        this.collateralValue = result.collateralValue();
        this.collateralRecoverable = result.collateralRecoverable();
        this.otherAssetsValue = input.otherAssetsValue();
        this.realizationRate = input.realizationRate();
        this.successProbability = input.successProbability();
        this.expectedCosts = result.expectedCosts();
        this.estimatedRecoverable = result.estimatedRecoverable();
        this.netBenefit = result.netBenefit();
        this.costBenefitRatio = result.costBenefitRatio();
        this.economicallyViable = result.economicallyViable();
        this.viable = viable;
        this.recommendedRoutes = join(routes);
        this.availableRoutes = join(result.availableRoutes().stream().sorted().toList());
        this.suggestedRoutes = join(result.suggestedRoutes());
        this.flags = result.flags().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        this.checklist = new ArrayList<>(items);
        this.assetSearchNotes = assetSearchNotes == null || assetSearchNotes.isBlank() ? null : assetSearchNotes.trim();
        this.opinion = parecer;
        this.collateralSnapshot = collateralSnapshot;
        this.prescriptionSnapshot = prescriptionSnapshot;
    }

    private static String join(List<LegalRoute> routes) {
        return routes.isEmpty() ? null : routes.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private static List<LegalRoute> split(String text) {
        return text == null || text.isBlank() ? List.of()
                : Arrays.stream(text.split(",")).map(LegalRoute::valueOf).toList();
    }

    public LegalRoute primaryRoute() {
        List<LegalRoute> routes = getRecommendedRoutes();
        return routes.isEmpty() ? null : routes.getFirst();
    }

    public UUID getMatterId() {
        return matterId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public Money getDebtAmount() {
        return debtAmount;
    }

    public Money getCollectibleDebt() {
        return collectibleDebt;
    }

    public Money getCollateralValue() {
        return collateralValue;
    }

    public Money getCollateralRecoverable() {
        return collateralRecoverable;
    }

    public Money getOtherAssetsValue() {
        return otherAssetsValue;
    }

    public Percentage getRealizationRate() {
        return realizationRate;
    }

    public Percentage getSuccessProbability() {
        return successProbability;
    }

    public Money getExpectedCosts() {
        return expectedCosts;
    }

    public Money getEstimatedRecoverable() {
        return estimatedRecoverable;
    }

    public Money getNetBenefit() {
        return netBenefit;
    }

    public BigDecimal getCostBenefitRatio() {
        return costBenefitRatio;
    }

    public boolean isEconomicallyViable() {
        return economicallyViable;
    }

    public boolean isViable() {
        return viable;
    }

    public List<LegalRoute> getRecommendedRoutes() {
        return split(recommendedRoutes);
    }

    public List<LegalRoute> getAvailableRoutes() {
        return split(availableRoutes);
    }

    public List<LegalRoute> getSuggestedRoutes() {
        return split(suggestedRoutes);
    }

    public Set<MatterFlag> getFlags() {
        if (flags == null || flags.isBlank()) {
            return Set.of();
        }
        Set<MatterFlag> set = EnumSet.noneOf(MatterFlag.class);
        Arrays.stream(flags.split(",")).map(MatterFlag::valueOf).forEach(set::add);
        return Set.copyOf(set);
    }

    public List<ChecklistItem> getChecklist() {
        return List.copyOf(checklist);
    }

    public String getCollateralSnapshot() {
        return collateralSnapshot;
    }

    public String getPrescriptionSnapshot() {
        return prescriptionSnapshot;
    }

    public String getAssetSearchNotes() {
        return assetSearchNotes;
    }

    public String getOpinion() {
        return opinion;
    }
}
