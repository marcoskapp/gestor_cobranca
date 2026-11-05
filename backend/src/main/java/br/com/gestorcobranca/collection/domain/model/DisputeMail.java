package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** E-mail de um pedido ao credor (pedido, lembrete ou cobrança do retorno vencido), enviado por job com retentativas. */
@Entity
@Table(name = "dispute_mail", schema = "collection")
public class DisputeMail extends TenantScopedEntity {

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20, updatable = false)
    private DisputeMailPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DisputeMailStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    protected DisputeMail() {
    }

    public DisputeMail(UUID requestId, DisputeMailPurpose purpose) {
        this.requestId = requestId;
        this.purpose = purpose;
        this.status = DisputeMailStatus.QUEUED;
    }

    public boolean isQueued() {
        return status == DisputeMailStatus.QUEUED;
    }

    public void recordAttempt(int attempt) {
        this.attempts = Math.max(attempts, attempt);
    }

    public void markSent(Instant now) {
        status = DisputeMailStatus.SENT;
        sentAt = now;
    }

    public void markFailed(String code, Instant now) {
        status = DisputeMailStatus.FAILED;
        failureCode = code == null || code.isBlank() ? "UNKNOWN" : code.length() > 60 ? code.substring(0, 60) : code;
        failedAt = now;
    }

    /** Não é mais necessário (pedido respondido ou cancelado antes do envio). */
    public void skip(Instant now) {
        status = DisputeMailStatus.SKIPPED;
        failedAt = null;
        sentAt = null;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public DisputeMailPurpose getPurpose() {
        return purpose;
    }

    public DisputeMailStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public String getFailureCode() {
        return failureCode;
    }
}
