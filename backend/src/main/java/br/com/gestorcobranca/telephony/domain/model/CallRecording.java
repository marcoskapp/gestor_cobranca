package br.com.gestorcobranca.telephony.domain.model;

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
 * Gravação da chamada: metadados e arquivo no armazenamento próprio (S3, categoria
 * {@code CALL_RECORDING}, chave prefixada pelo tenant, SHA-256 de integridade). O arquivo é
 * descartado pela política {@code CALL_RECORDINGS} do compliance ou pela anonimização do devedor;
 * os metadados permanecem. Todo acesso exige permissão específica e é registrado no log de acesso.
 */
@Entity
@Table(name = "call_recording", schema = "telephony")
public class CallRecording extends TenantScopedEntity {

    @Column(name = "call_id", nullable = false, updatable = false)
    private UUID callId;

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Column(name = "debtor_id")
    private UUID debtorId;

    @Column(name = "provider_recording_ref", nullable = false, length = 200, updatable = false)
    private String providerRecordingRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RecordingStatus status;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "file_key", length = 500)
    private String fileKey;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "announced", nullable = false, updatable = false)
    private boolean announced;

    @Column(name = "available_at", nullable = false, updatable = false)
    private Instant availableAt;

    @Column(name = "stored_at")
    private Instant storedAt;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "purged_at")
    private Instant purgedAt;

    @Column(name = "purge_reason", length = 30)
    private String purgeReason;

    @Column(name = "access_count", nullable = false)
    private int accessCount;

    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;

    protected CallRecording() {
    }

    public CallRecording(Call call, String providerRecordingRef, Integer durationSeconds, Instant at) {
        Guard.require(call != null, "recording.call_required", "Chamada obrigatória");
        Guard.require(providerRecordingRef != null && !providerRecordingRef.isBlank()
                && providerRecordingRef.length() <= 200, "recording.ref_invalid", "Referência da gravação inválida");
        this.callId = call.getId();
        this.creditorId = call.getCreditorId();
        this.debtorId = call.getDebtorId();
        this.providerRecordingRef = providerRecordingRef;
        this.durationSeconds = durationSeconds;
        this.announced = call.isRecordingAnnounced();
        this.availableAt = at;
        this.status = RecordingStatus.PENDING;
    }

    public void stored(String fileKey, String contentType, long sizeBytes, String sha256, Instant at) {
        Guard.requireState(status == RecordingStatus.PENDING, "recording.not_pending", "Gravação já processada");
        this.fileKey = fileKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storedAt = at;
        this.status = RecordingStatus.STORED;
    }

    public void failed(String code) {
        if (status == RecordingStatus.PENDING) {
            this.status = RecordingStatus.FAILED;
            this.failureCode = code;
        }
    }

    /** @return chave do arquivo a remover (nula se não havia arquivo) */
    public String purge(String reason, Instant at) {
        if (status == RecordingStatus.PURGED) {
            return null;
        }
        String key = fileKey;
        this.status = RecordingStatus.PURGED;
        this.fileKey = null;
        this.purgedAt = at;
        this.purgeReason = reason;
        return key;
    }

    public void accessed(Instant at) {
        Guard.requireState(status == RecordingStatus.STORED, "recording.unavailable", "Gravação indisponível");
        this.accessCount++;
        this.lastAccessedAt = at;
    }

    /** Vínculo com o devedor identificado depois (chamada recebida). */
    public void linkDebtor(UUID debtorId) {
        this.debtorId = debtorId;
    }

    public UUID getCallId() {
        return callId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public String getProviderRecordingRef() {
        return providerRecordingRef;
    }

    public RecordingStatus getStatus() {
        return status;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public String getFileKey() {
        return fileKey;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public boolean isAnnounced() {
        return announced;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public Instant getStoredAt() {
        return storedAt;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public Instant getPurgedAt() {
        return purgedAt;
    }

    public String getPurgeReason() {
        return purgeReason;
    }

    public int getAccessCount() {
        return accessCount;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }
}
