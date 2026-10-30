package br.com.gestorcobranca.ingestion.domain.model.pdf;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
import java.util.UUID;

/**
 * Revisão do mapa de extração de uma importação (append-only): quem escreveu (IA, mapa salvo, usuário, leitura
 * visual), o mapa completo e o resultado resumido das verificações. É o histórico do "mapa" que o usuário validou.
 * O mapa descreve posições e rótulos, nunca valores do devedor.
 */
@Entity
@Table(name = "pdf_map_revision", schema = "ingestion")
public class PdfMapRevision extends TenantScopedEntity {

    @Column(name = "pdf_import_id", nullable = false, updatable = false)
    private UUID pdfImportId;

    @Column(name = "revision", nullable = false, updatable = false)
    private int revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, updatable = false, length = 20)
    private MapOrigin origin;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "map", nullable = false, updatable = false)
    private String map;

    @Column(name = "records", nullable = false, updatable = false)
    private int records;

    @Column(name = "totals_match", nullable = false, updatable = false)
    private boolean totalsMatch;

    @Column(name = "blocking", nullable = false, updatable = false)
    private boolean blocking;

    @Column(name = "template_id", updatable = false)
    private UUID templateId;

    protected PdfMapRevision() {
    }

    public PdfMapRevision(UUID pdfImportId, int revision, MapOrigin origin, String map, int records, boolean totalsMatch,
                          boolean blocking, UUID templateId) {
        this.pdfImportId = Objects.requireNonNull(pdfImportId);
        this.revision = revision;
        this.origin = Objects.requireNonNull(origin);
        this.map = Objects.requireNonNull(map);
        this.records = records;
        this.totalsMatch = totalsMatch;
        this.blocking = blocking;
        this.templateId = templateId;
    }

    public UUID getPdfImportId() {
        return pdfImportId;
    }

    public int getRevision() {
        return revision;
    }

    public MapOrigin getOrigin() {
        return origin;
    }

    public String getMap() {
        return map;
    }

    public int getRecords() {
        return records;
    }

    public boolean isTotalsMatch() {
        return totalsMatch;
    }

    public boolean isBlocking() {
        return blocking;
    }

    public UUID getTemplateId() {
        return templateId;
    }
}
