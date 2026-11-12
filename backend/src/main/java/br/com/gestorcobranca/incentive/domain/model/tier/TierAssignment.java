package br.com.gestorcobranca.incentive.domain.model.tier;

import br.com.gestorcobranca.shared.domain.Guard;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Pessoa numa rodada: a classificação proposta (imutável) e o nível final, que a revisão humana pode ajustar com
 * motivo (ex.: férias, troca de carteira, licença).
 */
public final class TierAssignment {

    public static final int MIN_REASON = 10;
    public static final int MAX_REASON = 2000;

    private final UUID roundId;
    private final TierProposal proposal;
    private Tier finalTier;
    private UUID adjustedBy;
    private Instant adjustedAt;
    private String adjustmentReason;

    public TierAssignment(UUID roundId, TierProposal proposal, Tier finalTier, UUID adjustedBy, Instant adjustedAt,
                          String adjustmentReason) {
        this.roundId = Objects.requireNonNull(roundId);
        this.proposal = Objects.requireNonNull(proposal);
        this.finalTier = Objects.requireNonNull(finalTier);
        this.adjustedBy = adjustedBy;
        this.adjustedAt = adjustedAt;
        this.adjustmentReason = adjustmentReason;
    }

    public static TierAssignment proposed(UUID roundId, TierProposal proposal) {
        return new TierAssignment(roundId, proposal, proposal.proposedTier(), null, null, null);
    }

    /** Ajuste do nível final com motivo (10 a 2000 caracteres); pode voltar ao proposto, sempre com motivo. */
    public void adjust(Tier tier, String reason, UUID by, Instant at) {
        Guard.require(tier != null, "tier_assignment.tier_required", "Informe o nível");
        String text = reason == null ? "" : reason.strip();
        Guard.require(text.length() >= MIN_REASON && text.length() <= MAX_REASON, "tier_assignment.reason_invalid",
                "Explique o ajuste em 10 a 2000 caracteres");
        finalTier = tier;
        adjustedBy = Objects.requireNonNull(by);
        adjustedAt = Objects.requireNonNull(at);
        adjustmentReason = text;
    }

    public boolean adjusted() {
        return adjustedBy != null;
    }

    public UUID roundId() {
        return roundId;
    }

    public UUID userId() {
        return proposal.userId();
    }

    public TierProposal proposal() {
        return proposal;
    }

    public Tier finalTier() {
        return finalTier;
    }

    public UUID adjustedBy() {
        return adjustedBy;
    }

    public Instant adjustedAt() {
        return adjustedAt;
    }

    public String adjustmentReason() {
        return adjustmentReason;
    }
}
