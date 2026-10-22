package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Período em que um título esteve sob um credor/carteira (RB01). Aberto na criação do título,
 * encerrado em transferência de carteira ou devolução; o histórico nunca é apagado. Há no
 * máximo um período aberto por título (índice único parcial).
 */
@Entity
@Table(name = "title_ownership", schema = "debt")
public class TitleOwnership extends TenantScopedEntity {

    @Column(name = "title_id", nullable = false, updatable = false)
    private UUID titleId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_type", length = 20)
    private OwnershipReleaseType releaseType;

    @Column(name = "release_reason", length = 500)
    private String releaseReason;

    protected TitleOwnership() {
    }

    public TitleOwnership(UUID titleId, UUID creditorId, UUID portfolioId, Instant assignedAt) {
        this.titleId = Objects.requireNonNull(titleId, "titleId");
        this.creditorId = Objects.requireNonNull(creditorId, "creditorId");
        this.portfolioId = Objects.requireNonNull(portfolioId, "portfolioId");
        this.assignedAt = Objects.requireNonNull(assignedAt, "assignedAt");
    }

    public void release(Instant at, OwnershipReleaseType type, String reason) {
        Guard.requireState(releasedAt == null, "title_ownership.already_released", "Período de titularidade já encerrado");
        Objects.requireNonNull(type, "type");
        Guard.require(at != null && !at.isBefore(assignedAt), "title_ownership.release_invalid",
                "Encerramento anterior ao início da titularidade");
        this.releasedAt = at;
        this.releaseType = type;
        this.releaseReason = reason;
    }

    public UUID getTitleId() {
        return titleId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public OwnershipReleaseType getReleaseType() {
        return releaseType;
    }

    public String getReleaseReason() {
        return releaseReason;
    }
}
