package br.com.gestorcobranca.ingestion.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Registro do bloqueio de um lote por titular anonimizado (SEG-20261124-D01h, decisão 1488): o que o bloqueio fez com
 * o lote, a conferência fechada, o aviso ao credor (compliance) e as linhas do titular no arquivo, com a referência do
 * título mascarada — a base do pedido de novo arquivo ao credor. Imutável; um por lote e evento de anonimização.
 */
@Entity
@Table(name = "import_subject_block", schema = "ingestion")
public class SubjectBlock extends TenantScopedEntity {

    /** Como a linha do titular foi encontrada. */
    public enum MatchedBy { DEBTOR, TITLE_REFERENCE }

    /** Linhas guardadas por lote (o restante conta em {@code lines}). */
    public static final int MAX_LINES = 500;
    public static final int MAX_REFERENCE = 1440;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "notice_id", updatable = false)
    private UUID noticeId;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect", nullable = false, length = 20, updatable = false)
    private BlockEffect effect;

    @Column(name = "review_id", updatable = false)
    private UUID reviewId;

    /** Linhas do titular encontradas no arquivo (pode passar de {@link #MAX_LINES}). */
    @Column(name = "lines", nullable = false, updatable = false)
    private int lines;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "import_subject_block_line", schema = "ingestion", joinColumns = @JoinColumn(name = "block_id"))
    @OrderColumn(name = "position")
    private List<Line> lineList = new ArrayList<>();

    protected SubjectBlock() {
    }

    /**
     * @param noticeId aviso ao credor do compliance (nulo se o credor do lote não tem título conservado do titular)
     * @param reviewId conferência fechada pelo bloqueio (nula se não havia)
     */
    public SubjectBlock(UUID batchId, UUID creditorId, UUID noticeId, UUID sourceEventId, BlockEffect effect,
                        UUID reviewId, List<Line> found) {
        Guard.require(batchId != null && creditorId != null && sourceEventId != null && effect != null,
                "import_subject_block.required", "Lote, credor, evento e efeito obrigatórios");
        this.batchId = batchId;
        this.creditorId = creditorId;
        this.noticeId = noticeId;
        this.sourceEventId = sourceEventId;
        this.effect = effect;
        this.reviewId = reviewId;
        List<Line> safe = found == null ? List.of() : found;
        this.lines = safe.size();
        this.lineList = new ArrayList<>(safe.subList(0, Math.min(safe.size(), MAX_LINES)));
    }

    public UUID getBatchId() { return batchId; }
    public UUID getCreditorId() { return creditorId; }
    public UUID getNoticeId() { return noticeId; }
    public UUID getSourceEventId() { return sourceEventId; }
    public BlockEffect getEffect() { return effect; }
    public UUID getReviewId() { return reviewId; }
    public int getLines() { return lines; }
    public List<Line> getLineList() { return List.copyOf(lineList); }

    /** Linha do titular: número no arquivo, referência do título mascarada e como foi encontrada. */
    @Embeddable
    public static class Line {

        @Column(name = "row_number", nullable = false, updatable = false)
        private long rowNumber;

        @Column(name = "reference", nullable = false, length = MAX_REFERENCE, updatable = false)
        private String reference;

        @Enumerated(EnumType.STRING)
        @Column(name = "matched_by", nullable = false, length = 20, updatable = false)
        private MatchedBy matchedBy;

        protected Line() {
        }

        /** @param maskedReference referência do título com CPF/CNPJ embutido já mascarado */
        public Line(long rowNumber, String maskedReference, MatchedBy matchedBy) {
            Guard.require(rowNumber > 0, "import_subject_block.row_invalid", "Linha inválida");
            Guard.require(maskedReference != null && !maskedReference.isBlank()
                    && maskedReference.length() <= MAX_REFERENCE, "import_subject_block.reference_invalid",
                    "Referência inválida");
            Guard.require(matchedBy != null, "import_subject_block.match_required", "Forma de encontro obrigatória");
            this.rowNumber = rowNumber;
            this.reference = maskedReference;
            this.matchedBy = matchedBy;
        }

        public long getRowNumber() { return rowNumber; }
        public String getReference() { return reference; }
        public MatchedBy getMatchedBy() { return matchedBy; }
    }
}
