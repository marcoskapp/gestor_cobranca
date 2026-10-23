package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Versão da régua de uma campanha ({@link CadenceDefinition} em JSON). Rascunho editável; publicar
 * retira a versão anterior e torna esta a usada nas novas inscrições — inscrições em andamento
 * continuam na versão com que começaram. Publicada é imutável (gatilho no banco).
 */
@Entity
@Table(name = "cadence", schema = "campaign")
public class Cadence extends TenantScopedEntity {

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CadenceStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", nullable = false)
    private String definition;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "retired_at")
    private Instant retiredAt;

    protected Cadence() {
    }

    public Cadence(UUID campaignId, int versionNumber, String definitionJson) {
        this.campaignId = campaignId;
        this.versionNumber = versionNumber;
        this.status = CadenceStatus.DRAFT;
        this.definition = definitionJson;
    }

    public void updateDefinition(String definitionJson) {
        Guard.requireState(status == CadenceStatus.DRAFT, "cadence.not_draft",
                "Versão publicada é imutável: crie uma nova versão");
        this.definition = definitionJson;
    }

    public void publish(Instant now, UUID actorId) {
        Guard.requireState(status == CadenceStatus.DRAFT, "cadence.not_draft", "Somente rascunhos são publicados");
        this.status = CadenceStatus.PUBLISHED;
        this.publishedAt = now;
        this.publishedBy = actorId;
    }

    public void retire(Instant now) {
        Guard.requireState(status == CadenceStatus.PUBLISHED, "cadence.not_published", "Versão não publicada");
        this.status = CadenceStatus.RETIRED;
        this.retiredAt = now;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public CadenceStatus getStatus() {
        return status;
    }

    public String getDefinition() {
        return definition;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public UUID getPublishedBy() {
        return publishedBy;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }
}
