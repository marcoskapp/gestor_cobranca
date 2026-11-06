package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Limite da assessoria para aplicar uma <b>redução</b> manual do valor do título sem segunda pessoa (RB06). Enquanto não
 * gravado, vale {@link #DEFAULT_THRESHOLD}. Zero: toda correção passa por aprovação. Aumentos sempre passam.
 * <p>
 * Reduzir o limite (mais restritivo) vale na hora. <b>Aumentar</b> fica proposto até <b>outra pessoa</b> confirmar
 * (decisão 805: a segregação vale também para o administrador); sem isso, quem aprova subiria o limite, aplicaria a
 * própria redução sem segunda pessoa e voltaria o limite. A proposta <b>vence</b> depois de um prazo (padrão 15 dias,
 * decisão do responsável sobre a 865): vencida, não é mais confirmada e é descartada pelo job diário.
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

    /**
     * Confirma o aumento proposto: só outra pessoa (nem o administrador confirma a própria proposta), só o valor que ela
     * revisou — se a proposta mudou entre a leitura e a confirmação, recusa (409) para nova conferência — e só enquanto a
     * proposta não venceu ({@code validity} depois de {@code proposedAt}; vencida → 409
     * {@code title_amount.proposal_expired}).
     */
    public void confirmProposal(UUID by, Money reviewedThreshold, Instant now, Duration validity) {
        Guard.requireState(proposedThreshold != null, "title_amount.no_threshold_proposal",
                "Não há aumento de limite aguardando confirmação");
        Guard.requireState(!proposedBy.equals(by), "title_amount.self_approval",
                "Quem propôs o aumento do limite não pode confirmá-lo");
        Guard.requireState(!isProposalExpired(now, validity), "title_amount.proposal_expired",
                "A proposta de aumento do limite venceu sem confirmação; quem quiser o aumento precisa propor de novo");
        Guard.requireState(reviewedThreshold != null && proposedThreshold.compareTo(reviewedThreshold) == 0,
                "title_amount.proposal_changed",
                "A proposta de aumento do limite mudou desde que você a abriu; confira o novo valor");
        this.approvalThreshold = proposedThreshold;
        clearProposal();
    }

    public boolean hasProposal() {
        return proposedThreshold != null;
    }

    /** Fim do prazo para confirmar a proposta em aberto (nulo sem proposta). */
    public Instant proposalExpiresAt(Duration validity) {
        return proposedAt == null ? null : proposedAt.plus(validity);
    }

    /** Proposta em aberto cujo prazo para confirmar já passou. */
    public boolean isProposalExpired(Instant now, Duration validity) {
        return proposedAt != null && !now.isBefore(proposedAt.plus(validity));
    }

    /**
     * Descarta a proposta vencida (job diário); o limite vigente não muda.
     *
     * @return {@code true} se havia proposta vencida e ela foi descartada
     */
    public boolean discardExpiredProposal(Instant now, Duration validity) {
        if (!isProposalExpired(now, validity)) {
            return false;
        }
        clearProposal();
        return true;
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
