package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Evidência anexada a uma contestação (append-only): arquivo no armazenamento de evidências
 * (S3, categoria EVIDENCE) com hash SHA-256 para prova de integridade.
 */
@Entity
@Table(name = "dispute_evidence", schema = "collection")
public class DisputeEvidence extends TenantScopedEntity {

    public static final int MAX_PER_DISPUTE = 20;

    @Column(name = "dispute_id", nullable = false, updatable = false)
    private UUID disputeId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "file_key", nullable = false, length = 512, updatable = false)
    private String fileKey;

    @Column(name = "file_name", nullable = false, length = 200, updatable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long size;

    @Column(name = "sha256", nullable = false, length = 64, updatable = false)
    private String sha256;

    protected DisputeEvidence() {
    }

    public DisputeEvidence(Dispute dispute, String fileKey, String fileName, String contentType, long size,
                           String sha256) {
        dispute.requireOpenForEvidence();
        Guard.require(fileKey != null && sha256 != null, "dispute.evidence_invalid", "Arquivo de evidência inválido");
        this.disputeId = dispute.getId();
        this.creditorId = dispute.getCreditorId();
        this.fileKey = fileKey;
        this.fileName = fileName == null || fileName.isBlank() ? "evidencia"
                : fileName.length() > 200 ? fileName.substring(0, 200) : fileName;
        this.contentType = contentType;
        this.size = size;
        this.sha256 = sha256;
    }

    public UUID getDisputeId() {
        return disputeId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public String getFileKey() {
        return fileKey;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSize() {
        return size;
    }

    public String getSha256() {
        return sha256;
    }
}
