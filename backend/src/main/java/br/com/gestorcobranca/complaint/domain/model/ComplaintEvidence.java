package br.com.gestorcobranca.complaint.domain.model;

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
 * Evidência da reclamação (notificação do Procon, print do consumidor.gov.br, resposta enviada). Os arquivos vêm de
 * fora da assessoria, então seguem o mesmo pipeline dos documentos do credor nas contestações: tipo conferido pelo
 * conteúdo (PDF, PNG, JPEG), gravação na quarentena, verificação de malware pelo job
 * {@code complaint.evidence_scan} e só então cópia para EVIDENCE com SHA-256. Com a verificação desligada
 * ({@code app.storage.malware-scan.mode=disabled}) o arquivo é liberado como {@code NOT_SCANNED}.
 */
@Entity
@Table(name = "complaint_evidence", schema = "complaint")
public class ComplaintEvidence extends TenantScopedEntity {

    public static final int MAX_PER_COMPLAINT = 20;

    /** {@code REMOVED}: arquivo apagado na anonimização do titular (fica só o registro: nome, tamanho e SHA-256). */
    public enum Status { PENDING_SCAN, AVAILABLE, REJECTED, FAILED, REMOVED }

    public enum Verdict { CLEAN, NOT_SCANNED, INFECTED }

    @Column(name = "complaint_id", nullable = false, updatable = false)
    private UUID complaintId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "file_key", length = 512)
    private String fileKey;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long size;

    @Column(name = "sha256", nullable = false, length = 64, updatable = false)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", length = 15)
    private Verdict verdict;

    @Column(name = "engine", length = 30)
    private String engine;

    @Column(name = "signature", length = 200)
    private String signature;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "scanned_at")
    private Instant scannedAt;

    protected ComplaintEvidence() {
    }

    /** Arquivo na quarentena, aguardando a verificação. */
    public ComplaintEvidence(Complaint complaint, String quarantineKey, String fileName, String contentType, long size,
                             String sha256) {
        this.complaintId = complaint.getId();
        this.creditorId = complaint.getCreditorId();
        this.fileKey = Guard.requireNonNull(quarantineKey, "complaint.evidence_key_required", "Arquivo obrigatório");
        this.fileName = fileName == null || fileName.isBlank() ? "evidencia" : fileName.strip();
        this.contentType = contentType;
        this.size = size;
        this.sha256 = sha256;
        this.status = Status.PENDING_SCAN;
    }

    public void attempt() {
        attempts++;
    }

    /** Liberado (limpo ou sem verificação): passa a apontar para o arquivo em EVIDENCE. */
    public void release(Verdict result, String engineName, String evidenceKey, Instant now) {
        Guard.requireState(status == Status.PENDING_SCAN, "complaint.evidence_not_pending", "Evidência já verificada");
        this.verdict = result;
        this.engine = engineName;
        this.fileKey = evidenceKey;
        this.status = Status.AVAILABLE;
        this.scannedAt = now;
    }

    public void infected(String engineName, String threat, Instant now) {
        Guard.requireState(status == Status.PENDING_SCAN, "complaint.evidence_not_pending", "Evidência já verificada");
        this.verdict = Verdict.INFECTED;
        this.engine = engineName;
        this.signature = threat == null ? null : threat.length() > 200 ? threat.substring(0, 200) : threat;
        this.fileKey = null;
        this.status = Status.REJECTED;
        this.scannedAt = now;
    }

    /** Verificador indisponível em todas as tentativas: nunca liberado sem veredito. */
    public void failed(Instant now) {
        if (status == Status.PENDING_SCAN) {
            this.status = Status.FAILED;
            this.scannedAt = now;
        }
    }

    /** Anonimização do titular: o chamador apaga o arquivo; o registro fica sem a chave. */
    public String remove(Instant now) {
        String key = fileKey;
        this.fileKey = null;
        this.status = Status.REMOVED;
        this.scannedAt = scannedAt == null ? now : scannedAt;
        return key;
    }

    public boolean isPending() {
        return status == Status.PENDING_SCAN;
    }

    public UUID getComplaintId() { return complaintId; }
    public UUID getCreditorId() { return creditorId; }
    public String getFileKey() { return fileKey; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSize() { return size; }
    public String getSha256() { return sha256; }
    public Status getStatus() { return status; }
    public Verdict getVerdict() { return verdict; }
    public String getEngine() { return engine; }
    public String getSignature() { return signature; }
    public int getAttempts() { return attempts; }
    public Instant getScannedAt() { return scannedAt; }
}
