package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Limite da assessoria para aplicar uma <b>redução</b> manual do valor do título sem segunda pessoa (RB06). Enquanto não
 * gravado, vale {@link #DEFAULT_THRESHOLD}. Zero: toda correção passa por aprovação. Aumentos sempre passam.
 * <p>
 * Reduzir o limite (mais restritivo) vale na hora. <b>Aumentar</b> fica proposto até <b>outra pessoa</b> confirmar
 * (decisão 805: a segregação vale também para o administrador); sem isso, quem aprova subiria o limite, aplicaria a
 * própria redução sem segunda pessoa e voltaria o limite.
 */
@Entity
@Table(name = "amount_adjustment_settings", schema = "debt")
public class AmountAdjustmentSettings extends TenantScopedEntity {

    public static final Money DEFAULT_THRESHOLD = Money.of("200.00");
    public static final Money MAX_THRESHOLD = Money.of("100000.00");

    @Column(name = "approval_threshold", nullable = false, precision = 19, scale = 2)
    private Money approvalThreshold;

    @Column(name = "proposed_threshold", precision = 19, scale = 2)
    private Money proposedThreshold;

    @Column(name = "proposed_by")
    private UUID proposedBy;

    @Column(name = "proposed_by_name", length = TitleAmountAdjustment.NAME_MAX)
    private String proposedByName;

    @Column(name = "proposed_at")
    private Instant proposedAt;

    protected AmountAdjustmentSettings() {
    }

    /**
     * Primeira gravação da assessoria: parte do padrão, então aumentar sobre ele também fica proposto.
     *
     * @see #change(Money, UUID, String, Instant)
     */
    public AmountAdjustmentSettings(Money threshold, UUID by, String byName, Instant at) {
        this.approvalThreshold = DEFAULT_THRESHOLD;
        change(threshold, by, byName, at);
    }

    /**
     * Reduzir ou manter o limite vale na hora e descarta a proposta de aumento em aberto; aumentar fica proposto até
     * outra pessoa confirmar (substitui a proposta anterior).
     *
     * @return {@code true} se o limite mudou agora; {@code false} se ficou proposto
     */
    public final boolean change(Money threshold, UUID by, String byName, Instant at) {
        Guard.require(threshold != null && !threshold.isNegative() && !threshold.isGreaterThan(MAX_THRESHOLD),
                "title_amount.threshold_invalid", "Limite entre R$ 0,00 e R$ 100.000,00");
        if (!threshold.isGreaterThan(approvalThreshold)) {
            this.approvalThreshold = threshold;
            clearProposal();
            return true;
        }
        this.proposedThreshold = threshold;
        this.proposedBy = Objects.requireNonNull(by);
        this.proposedByName = byName == null || byName.length() <= TitleAmountAdjustment.NAME_MAX ? byName
                : byName.substring(0, TitleAmountAdjustment.NAME_MAX);
        this.proposedAt = Objects.requireNonNull(at);
        return false;
    }

    /** Confirma o aumento proposto: só outra pessoa (nem o administrador confirma a própria proposta). */
    public void confirmProposal(UUID by) {
        Guard.requireState(proposedThreshold != null, "title_amount.no_threshold_proposal",
                "Não há aumento de limite aguardando confirmação");
        Guard.requireState(!proposedBy.equals(by), "title_amount.self_approval",
                "Quem propôs o aumento do limite não pode confirmá-lo");
        this.approvalThreshold = proposedThreshold;
        clearProposal();
    }

    public boolean hasProposal() {
        return proposedThreshold != null;
    }

    private void clearProposal() {
        this.proposedThreshold = null;
        this.proposedBy = null;
        this.proposedByName = null;
        this.proposedAt = null;
    }

    public Money getApprovalThreshold() {
        return approvalThreshold;
    }

    public Money getProposedThreshold() {
        return proposedThreshold;
    }

    public UUID getProposedBy() {
        return proposedBy;
    }

    public String getProposedByName() {
        return proposedByName;
    }

    public Instant getProposedAt() {
        return proposedAt;
    }
}
