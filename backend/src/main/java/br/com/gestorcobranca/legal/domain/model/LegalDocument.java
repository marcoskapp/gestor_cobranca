package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Documento ou evidência do caso jurídico guardado no {@code FileStorage} do tenant (chave,
 * nome, tipo, tamanho e SHA-256 de integridade), com vínculo opcional a um registro (autorização,
 * medida, processo, andamento, audiência, despesa). Append-only no banco; o download é registrado
 * no log de acesso.
 */
@Entity
@Table(name = "legal_document", schema = "legal")
public class LegalDocument extends TenantScopedEntity {

    @Column(name = "matter_id", nullable = false, updatable = false)
    private UUID matterId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30, updatable = false)
    private DocumentKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false, length = 20, updatable = false)
    private DocumentLink linkType;

    @Column(name = "link_id", updatable = false)
    private UUID linkId;

    @Column(name = "description", length = 300, updatable = false)
    private String description;

    @Column(name = "storage_key", nullable = false, length = 500, updatable = false)
    private String storageKey;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64, updatable = false)
    private String sha256;

    protected LegalDocument() {
    }

    public LegalDocument(UUID matterId, UUID creditorId, DocumentKind kind, DocumentLink linkType, UUID linkId,
                         String description, String storageKey, String fileName, String contentType, long sizeBytes,
                         String sha256) {
        Guard.require(kind != null, "legal_document.kind_required", "Natureza do documento obrigatória");
        DocumentLink link = linkType == null ? DocumentLink.MATTER : linkType;
        Guard.require((link == DocumentLink.MATTER) == (linkId == null), "legal_document.link_invalid",
                "Informe o registro vinculado (ou nenhum, para o caso jurídico)");
        Guard.require(description == null || description.trim().length() <= 300, "legal_document.description_too_long",
                "Descrição até 300 caracteres");
        this.matterId = matterId;
        this.creditorId = creditorId;
        this.kind = kind;
        this.linkType = link;
        this.linkId = linkId;
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.storageKey = storageKey;
        this.fileName = fileName == null || fileName.isBlank() ? "documento" : fileName.trim();
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
    }

    public UUID getMatterId() {
        return matterId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public DocumentKind getKind() {
        return kind;
    }

    public DocumentLink getLinkType() {
        return linkType;
    }

    public UUID getLinkId() {
        return linkId;
    }

    public String getDescription() {
        return description;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }
}
