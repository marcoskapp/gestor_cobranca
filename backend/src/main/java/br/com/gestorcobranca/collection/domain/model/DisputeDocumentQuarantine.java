package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Documento enviado pelo credor pela API pública, aguardando a verificação de malware antes de virar evidência da
 * contestação. Nunca é servido enquanto está aqui.
 */
@Entity
@Table(name = "dispute_document_quarantine", schema = "collection")
public class DisputeDocumentQuarantine extends TenantScopedEntity {

    public enum Status { PENDING, RELEASED, INFECTED, FAILED }

    public enum Verdict { CLEAN, INFECTED, NOT_SCANNED }

    @Column(name = "dispute_id", nullable = false, updatable = false)
    private UUID disputeId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "creditor_response_id", nullable = false, updatable = false)
    private UUID creditorResponseId;

    @Column(name = "file_key", nullable = false, length = 500, updatable = false)
    private String fileKey;

    @Column(name = "file_name", nullable = false, length = 200, updatable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long size;

    @Column(name = "sha256", nullable = false, length = 64, updatable = false)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", length = 20)
    private Verdict verdict;

    @Column(name = "engine", length = 30)
    private String engine;

    @Column(name = "signature", length = 200)
    private String signature;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "scanned_at")
    private Instant scannedAt;

    @Column(name = "evidence_id")
    private UUID evidenceId;

    protected DisputeDocumentQuarantine() {
    }

    public DisputeDocumentQuarantine(Dispute dispute, UUID creditorResponseId, String fileKey, String fileName,
                                     String contentType, long size, String sha256) {
        Guard.require(fileKey != null && sha256 != null && size > 0, "dispute.evidence_invalid", "Arquivo inválido");
        this.disputeId = dispute.getId();
        this.creditorId = dispute.getCreditorId();
        this.creditorResponseId = creditorResponseId;
        this.fileKey = fileKey;
        this.fileName = fileName == null || fileName.isBlank() ? "documento"
                : fileName.length() > 200 ? fileName.substring(0, 200) : fileName;
        this.contentType = contentType;
        this.size = size;
        this.sha256 = sha256;
        this.status = Status.PENDING;
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }

    public void attempt() {
        this.attempts++;
    }

    /** Limpo ou verificação desligada: virou a evidência informada. */
    public void release(Verdict verdict, String engine, UUID evidenceId, Instant now) {
        Guard.requireState(isPending(), "dispute.quarantine_closed", "Documento já verificado");
        Guard.require(verdict == Verdict.CLEAN || verdict == Verdict.NOT_SCANNED, "dispute.quarantine_verdict",
                "Só documento limpo é liberado");
        this.status = Status.RELEASED;
        this.verdict = verdict;
        this.engine = engine;
        this.evidenceId = evidenceId;
        this.scannedAt = now;
    }

    public void infected(String engine, String signature, Instant now) {
        Guard.requireState(isPending(), "dispute.quarantine_closed", "Documento já verificado");
        this.status = Status.INFECTED;
        this.verdict = Verdict.INFECTED;
        this.engine = engine;
        this.signature = signature == null ? null : signature.length() > 200 ? signature.substring(0, 200) : signature;
        this.scannedAt = now;
    }

    /** Tentativas esgotadas sem veredito: não é liberado. */
    public void failed(Instant now) {
        if (isPending()) {
            this.status = Status.FAILED;
            this.scannedAt = now;
        }
    }

    public UUID getDisputeId() {
        return disputeId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getCreditorResponseId() {
        return creditorResponseId;
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

    public Status getStatus() {
        return status;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public String getEngine() {
        return engine;
    }

    public String getSignature() {
        return signature;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getScannedAt() {
        return scannedAt;
    }

    public UUID getEvidenceId() {
        return evidenceId;
    }
}
