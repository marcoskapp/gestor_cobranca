package br.com.gestorcobranca.ingestion.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Registro de que a assessoria pediu ao credor um novo arquivo, sem as linhas do titular anonimizado (decisão 1497).
 * Uma vez por lote, sem texto livre; imutável (é a evidência do pedido).
 */
@Entity
@Table(name = "import_file_request", schema = "ingestion")
public class FileRequest extends TenantScopedEntity {

    /** Canal pelo qual o credor foi avisado. */
    public enum Channel { EMAIL, PHONE, LETTER, MEETING, OTHER }

    /** Relógio do navegador adiantado em relação ao servidor (como no aviso ao credor, decisão 1485). */
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(1);

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private Channel channel;

    @Column(name = "sent_by", nullable = false, updatable = false)
    private UUID sentBy;

    protected FileRequest() {
    }

    /**
     * @param batch lote bloqueado (o pedido não pode ser anterior ao bloqueio)
     */
    public FileRequest(ImportBatch batch, Instant sentAt, Channel channel, UUID sentBy, Instant now) {
        Guard.requireState(batch.isBlocked(), "import_file_request.not_blocked",
                "O lote não está bloqueado: não há novo arquivo a pedir");
        Guard.requireNonNull(sentAt, "import_file_request.sent_at_required", "Informe quando o credor foi avisado");
        Guard.requireNonNull(channel, "import_file_request.channel_required", "Informe o canal do pedido");
        Guard.require(sentBy != null, "import_file_request.actor_required", "Quem registra o pedido é obrigatório");
        Guard.require(!sentAt.isAfter(now.plus(FUTURE_TOLERANCE)), "import_file_request.sent_at_future",
                "A data do pedido não pode ser futura");
        Guard.require(!sentAt.isBefore(batch.getBlockedAt()), "import_file_request.sent_at_before_block",
                "A data do pedido não pode ser anterior ao bloqueio do lote");
        this.batchId = batch.getId();
        this.creditorId = batch.getCreditorId();
        this.sentAt = sentAt;
        this.channel = channel;
        this.sentBy = sentBy;
    }

    public UUID getBatchId() { return batchId; }
    public UUID getCreditorId() { return creditorId; }
    public Instant getSentAt() { return sentAt; }
    public Channel getChannel() { return channel; }
    public UUID getSentBy() { return sentBy; }
}
