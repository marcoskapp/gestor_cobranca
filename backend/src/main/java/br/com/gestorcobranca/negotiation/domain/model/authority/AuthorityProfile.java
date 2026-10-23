package br.com.gestorcobranca.negotiation.domain.model.authority;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Percentage;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Perfil de alçada (§4): quanto do envelope da política um ator pode usar sem aprovação. Por
 * tenant, opcionalmente restrito a credores ({@code creditorIds} vazio = todos). Perfis semeados
 * ({@code systemProfile}) podem ter os parâmetros ajustados, mas não o código.
 */
@Entity
@Table(name = "authority_profile", schema = "negotiation")
public class AuthorityProfile extends TenantScopedEntity {

    public static final int MAX_LEVEL = 99;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Column(name = "code", nullable = false, updatable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "system_profile", nullable = false, updatable = false)
    private boolean systemProfile;

    @Column(name = "max_concession_level", nullable = false)
    private int maxConcessionLevel;

    @Column(name = "max_discount_share", nullable = false, precision = 9, scale = 4)
    private Percentage maxDiscountShare;

    @Column(name = "max_installments", nullable = false)
    private int maxInstallments;

    @Column(name = "max_agreement_amount", precision = 19, scale = 2)
    private Money maxAgreementAmount;

    @Column(name = "min_down_payment_share", nullable = false, precision = 9, scale = 4)
    private Percentage minDownPaymentShare;

    @Column(name = "can_formalize", nullable = false)
    private boolean canFormalize;

    @Column(name = "can_approve", nullable = false)
    private boolean canApprove;

    @Column(name = "max_renegotiations", nullable = false)
    private int maxRenegotiations;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "authority_profile_creditor", schema = "negotiation",
            joinColumns = @JoinColumn(name = "profile_id"))
    @Column(name = "creditor_id", nullable = false)
    private Set<UUID> creditorIds = new HashSet<>();

    protected AuthorityProfile() {
    }

    public AuthorityProfile(String code, boolean systemProfile, Parameters parameters) {
        Guard.require(code != null && code.matches("[A-Z0-9_]{2,40}"), "authority.code_invalid",
                "Código do perfil: 2 a 40 caracteres em maiúsculas, dígitos ou _");
        this.code = code;
        this.systemProfile = systemProfile;
        define(parameters);
    }

    /**
     * @param maxAgreementAmount nulo = sem limite próprio de valor
     * @param creditorIds        vazio = todos os credores
     */
    public record Parameters(String name, String description, int maxConcessionLevel, BigDecimal maxDiscountShare,
                             int maxInstallments, BigDecimal maxAgreementAmount, BigDecimal minDownPaymentShare,
                             boolean canFormalize, boolean canApprove, int maxRenegotiations,
                             Collection<UUID> creditorIds) {
    }

    public void define(Parameters p) {
        Guard.require(p != null, "authority.parameters_required", "Parâmetros obrigatórios");
        Guard.require(p.name() != null && !p.name().isBlank() && p.name().trim().length() <= 120, "authority.name_invalid",
                "Nome obrigatório (até 120 caracteres)");
        Guard.require(p.description() == null || p.description().length() <= 500, "authority.description_too_long",
                "Descrição até 500 caracteres");
        Guard.require(p.maxConcessionLevel() >= 0 && p.maxConcessionLevel() <= MAX_LEVEL, "authority.level_invalid",
                "Nível máximo entre 0 e " + MAX_LEVEL);
        Guard.require(inPercentRange(p.maxDiscountShare()), "authority.discount_share_invalid",
                "Fração do desconto entre 0 e 100%");
        Guard.require(p.maxInstallments() >= 1 && p.maxInstallments() <= 360, "authority.installments_invalid",
                "Parcelas máximas entre 1 e 360");
        Guard.require(p.maxAgreementAmount() == null || p.maxAgreementAmount().signum() > 0, "authority.amount_invalid",
                "Valor máximo do acordo deve ser positivo");
        Guard.require(inPercentRange(p.minDownPaymentShare()) && p.minDownPaymentShare().compareTo(HUNDRED) < 0,
                "authority.down_payment_invalid", "Entrada mínima entre 0 e 100% (exclusive)");
        Guard.require(p.maxRenegotiations() >= 0 && p.maxRenegotiations() <= 10, "authority.renegotiations_invalid",
                "Renegociações entre 0 e 10");
        Guard.require(!p.canApprove() || p.canFormalize(), "authority.approve_requires_formalize",
                "Quem aprova propostas de outros precisa poder formalizar");
        this.name = p.name().trim();
        this.description = p.description();
        this.maxConcessionLevel = p.maxConcessionLevel();
        this.maxDiscountShare = Percentage.of(p.maxDiscountShare());
        this.maxInstallments = p.maxInstallments();
        this.maxAgreementAmount = p.maxAgreementAmount() == null ? null : Money.of(p.maxAgreementAmount());
        this.minDownPaymentShare = Percentage.of(p.minDownPaymentShare());
        this.canFormalize = p.canFormalize();
        this.canApprove = p.canApprove();
        this.maxRenegotiations = p.maxRenegotiations();
        this.creditorIds.clear();
        if (p.creditorIds() != null) {
            this.creditorIds.addAll(p.creditorIds());
        }
    }

    private static boolean inPercentRange(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.compareTo(HUNDRED) <= 0;
    }

    /** O perfil vale para o credor (sem restrição ou credor listado). */
    public boolean appliesTo(UUID creditorId) {
        return creditorIds.isEmpty() || creditorIds.contains(creditorId);
    }

    public AuthorityLimits limits() {
        return new AuthorityLimits(getId(), code, maxConcessionLevel, maxDiscountShare.value(), maxInstallments,
                maxAgreementAmount, minDownPaymentShare.value(), canFormalize, canApprove, maxRenegotiations);
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

    public boolean isSystemProfile() {
        return systemProfile;
    }

    public int getMaxConcessionLevel() {
        return maxConcessionLevel;
    }

    public Percentage getMaxDiscountShare() {
        return maxDiscountShare;
    }

    public int getMaxInstallments() {
        return maxInstallments;
    }

    public Money getMaxAgreementAmount() {
        return maxAgreementAmount;
    }

    public Percentage getMinDownPaymentShare() {
        return minDownPaymentShare;
    }

    public boolean isCanFormalize() {
        return canFormalize;
    }

    public boolean isCanApprove() {
        return canApprove;
    }

    public int getMaxRenegotiations() {
        return maxRenegotiations;
    }

    public Set<UUID> getCreditorIds() {
        return Set.copyOf(creditorIds);
    }
}
