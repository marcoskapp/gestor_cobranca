package br.com.gestorcobranca.ingestion.domain.model.pdf;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Mapa aprovado salvo para um credor, identificado pela impressão digital do layout. O próximo PDF do mesmo formato
 * recebe este mapa direto, sem IA. Salvar de novo o mesmo layout cria a versão seguinte e desativa a anterior.
 */
@Entity
@Table(name = "pdf_layout_template", schema = "ingestion")
public class PdfLayoutTemplate extends TenantScopedEntity {

    public static final int NAME_MAX = 120;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "name", nullable = false, length = NAME_MAX)
    private String name;

    @Column(name = "fingerprint", nullable = false, updatable = false, length = 64)
    private String fingerprint;

    @Column(name = "template_version", nullable = false, updatable = false)
    private int templateVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "map", nullable = false, updatable = false)
    private String map;

    @Column(name = "source_import_id", updatable = false)
    private UUID sourceImportId;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "uses", nullable = false)
    private int uses;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Column(name = "deactivated_by")
    private UUID deactivatedBy;

    protected PdfLayoutTemplate() {
    }

    public PdfLayoutTemplate(UUID creditorId, String name, String fingerprint, int templateVersion, String map,
                             UUID sourceImportId) {
        Guard.require(name != null && !name.isBlank(), "pdf_template.name_required", "Informe o nome do mapa");
        Guard.require(name.strip().length() <= NAME_MAX, "pdf_template.name_too_long", "Nome do mapa longo demais");
        this.creditorId = Objects.requireNonNull(creditorId);
        this.name = name.strip();
        this.fingerprint = Objects.requireNonNull(fingerprint);
        this.templateVersion = templateVersion;
        this.map = Objects.requireNonNull(map);
        this.sourceImportId = sourceImportId;
        this.active = true;
    }

    public void used(Instant now) {
        uses++;
        lastUsedAt = now;
    }

    public void deactivate(UUID actor, Instant now) {
        Guard.requireState(active, "pdf_template.inactive", "Mapa já desativado");
        active = false;
        deactivatedAt = now;
        deactivatedBy = actor;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public String getName() {
        return name;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public int getTemplateVersion() {
        return templateVersion;
    }

    public String getMap() {
        return map;
    }

    public UUID getSourceImportId() {
        return sourceImportId;
    }

    public boolean isActive() {
        return active;
    }

    public int getUses() {
        return uses;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getDeactivatedAt() {
        return deactivatedAt;
    }
}
