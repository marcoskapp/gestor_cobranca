package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * Resposta do credor a uma contestação (append-only), registrada pela assessoria (com os documentos como evidências)
 * ou enviada pelo credor pela API pública. A análise e a resolução continuam sendo da assessoria.
 */
@Entity
@Immutable
@Table(name = "dispute_creditor_response", schema = "collection")
public class DisputeCreditorResponse extends TenantScopedEntity {

    @Column(name = "dispute_id", nullable = false, updatable = false)
    private UUID disputeId;

    @Column(name = "request_id", updatable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, updatable = false)
    private CreditorResponseSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "position", nullable = false, length = 30, updatable = false)
    private CreditorPosition position;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note_encrypted", nullable = false, length = 24000, updatable = false)
    private String note;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    protected DisputeCreditorResponse() {
    }

    public DisputeCreditorResponse(Dispute dispute, UUID requestId, CreditorResponseSource source,
                                   CreditorPosition position, String note, Instant receivedAt, UUID recordedBy) {
        Guard.requireState(dispute.getStatus().isOpen(), "dispute.not_open", "Contestação já encerrada");
        Guard.require(source != null && position != null, "dispute_response.position_required",
                "Informe a posição do credor");
        Guard.require(note != null && !note.isBlank() && note.trim().length() <= Dispute.DESCRIPTION_MAX,
                "dispute_response.note_required", "Resumo da resposta obrigatório (até %d caracteres)"
                        .formatted(Dispute.DESCRIPTION_MAX));
        Guard.require(receivedAt != null, "dispute_response.received_required", "Data de recebimento obrigatória");
        this.disputeId = dispute.getId();
        this.requestId = requestId;
        this.source = source;
        this.position = position;
        this.note = note.trim();
        this.receivedAt = receivedAt;
        this.recordedBy = recordedBy;
    }

    public UUID getDisputeId() {
        return disputeId;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public CreditorResponseSource getSource() {
        return source;
    }

    public CreditorPosition getPosition() {
        return position;
    }

    public String getNote() {
        return note;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public UUID getRecordedBy() {
        return recordedBy;
    }
}
