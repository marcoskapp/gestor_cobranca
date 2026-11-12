package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Projeção do nível de desempenho publicado pelo {@code incentive} (evento {@code NegotiatorTierChanged}): uma linha
 * por pessoa e publicação, imutável. Vale, para a pessoa, a linha mais recente com {@code validFrom} até a data de
 * referência (empate pelo instante da publicação) — entregas fora de ordem e níveis com vigência futura não mudam o
 * nível corrente antes da hora. Nível nulo = sem nível (vale o nível padrão da assessoria). Só o nível: nunca nota,
 * quartil ou valores (distribuicao-por-desempenho.md §7).
 */
@Entity
@Table(name = "negotiator_tier", schema = "collection")
public class NegotiatorTier extends TenantScopedEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", length = 1, columnDefinition = "varchar(1)", updatable = false)
    private PerformanceTier tier;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;

    @Column(name = "publication_id", nullable = false, updatable = false)
    private UUID publicationId;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    protected NegotiatorTier() {
    }

    public NegotiatorTier(UUID userId, PerformanceTier tier, LocalDate validFrom, UUID publicationId,
                          Instant publishedAt) {
        Guard.require(userId != null, "negotiator_tier.user_required", "Pessoa obrigatória");
        Guard.require(validFrom != null, "negotiator_tier.valid_from_required", "Início da vigência obrigatório");
        Guard.require(publicationId != null, "negotiator_tier.publication_required", "Publicação obrigatória");
        Guard.require(publishedAt != null, "negotiator_tier.published_at_required", "Instante da publicação obrigatório");
        this.userId = userId;
        this.tier = tier;
        this.validFrom = validFrom;
        this.publicationId = publicationId;
        this.publishedAt = publishedAt;
    }

    public UUID getUserId() {
        return userId;
    }

    /** @return nulo = sem nível (vale o padrão) */
    public PerformanceTier getTier() {
        return tier;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public UUID getPublicationId() {
        return publicationId;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
