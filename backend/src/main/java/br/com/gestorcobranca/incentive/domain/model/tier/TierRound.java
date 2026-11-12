package br.com.gestorcobranca.incentive.domain.model.tier;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Ids;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Rodada de classificação (distribuicao-por-desempenho.md §3.3): apurada com os parâmetros da época (cópia em
 * {@link #settings()}), revista por pessoas e publicada até o prazo; sem publicação no prazo, expira e continua valendo
 * a publicação anterior.
 */
public final class TierRound {

    public enum Trigger { SCHEDULED, MANUAL }

    private final UUID id;
    private final LocalDate periodFrom;
    private final LocalDate periodTo;
    private final Trigger trigger;
    private final TierSettings settings;
    private final LocalDate reviewDeadline;
    private final Instant createdAt;
    private final UUID createdBy;
    private TierRoundStatus status;
    private Instant publishedAt;
    private UUID publishedBy;
    private LocalDate validFrom;
    private Instant expiredAt;

    public TierRound(UUID id, LocalDate periodFrom, LocalDate periodTo, Trigger trigger, TierSettings settings,
                     LocalDate reviewDeadline, Instant createdAt, UUID createdBy, TierRoundStatus status,
                     Instant publishedAt, UUID publishedBy, LocalDate validFrom, Instant expiredAt) {
        this.id = Objects.requireNonNull(id);
        this.periodFrom = Objects.requireNonNull(periodFrom);
        this.periodTo = Objects.requireNonNull(periodTo);
        this.trigger = Objects.requireNonNull(trigger);
        this.settings = Objects.requireNonNull(settings);
        this.reviewDeadline = Objects.requireNonNull(reviewDeadline);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.createdBy = createdBy;
        this.status = Objects.requireNonNull(status);
        this.publishedAt = publishedAt;
        this.publishedBy = publishedBy;
        this.validFrom = validFrom;
        this.expiredAt = expiredAt;
    }

    /**
     * Nova rodada proposta: janela de {@code settings.windowDays()} dias terminando ontem.
     *
     * @param createdBy quem pediu a apuração (nulo na rotina)
     */
    public static TierRound propose(LocalDate today, Trigger trigger, TierSettings settings, LocalDate reviewDeadline,
                                    Instant now, UUID createdBy) {
        LocalDate to = today.minusDays(1);
        LocalDate from = to.minusDays(settings.windowDays() - 1L);
        Guard.require(!reviewDeadline.isBefore(today), "tier_round.deadline_invalid", "Prazo de revisão no passado");
        return new TierRound(Ids.newId(), from, to, trigger, settings, reviewDeadline, now, createdBy,
                TierRoundStatus.PROPOSED, null, null, null, null);
    }

    /** Publica: os níveis finais passam a valer na data da publicação. Só rodada proposta e dentro do prazo. */
    public void publish(UUID by, Instant at, LocalDate today) {
        Guard.requireState(status == TierRoundStatus.PROPOSED, "tier_round.not_proposed",
                "Só uma rodada em revisão pode ser publicada");
        Guard.requireState(!isOverdue(today), "tier_round.review_expired",
                "O prazo de revisão desta rodada terminou; vale a classificação anterior");
        status = TierRoundStatus.PUBLISHED;
        publishedBy = Objects.requireNonNull(by);
        publishedAt = Objects.requireNonNull(at);
        validFrom = today;
    }

    /** Expira a rodada proposta cujo prazo terminou (nada muda: vale a publicação anterior). */
    public void expire(Instant at, LocalDate today) {
        Guard.requireState(status == TierRoundStatus.PROPOSED && isOverdue(today), "tier_round.not_expirable",
                "Só uma rodada em revisão com o prazo encerrado expira");
        status = TierRoundStatus.EXPIRED;
        expiredAt = Objects.requireNonNull(at);
    }

    /** Em revisão e ainda aceita ajuste? */
    public void requireOpen(LocalDate today) {
        Guard.requireState(status == TierRoundStatus.PROPOSED, "tier_round.not_proposed",
                "Esta rodada não está mais em revisão");
        Guard.requireState(!isOverdue(today), "tier_round.review_expired",
                "O prazo de revisão desta rodada terminou; vale a classificação anterior");
    }

    /** O prazo (último dia útil de revisão) já passou? */
    public boolean isOverdue(LocalDate today) {
        return today.isAfter(reviewDeadline);
    }

    public UUID id() {
        return id;
    }

    public LocalDate periodFrom() {
        return periodFrom;
    }

    public LocalDate periodTo() {
        return periodTo;
    }

    public Trigger trigger() {
        return trigger;
    }

    public TierSettings settings() {
        return settings;
    }

    public LocalDate reviewDeadline() {
        return reviewDeadline;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public TierRoundStatus status() {
        return status;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public UUID publishedBy() {
        return publishedBy;
    }

    public LocalDate validFrom() {
        return validFrom;
    }

    public Instant expiredAt() {
        return expiredAt;
    }
}
