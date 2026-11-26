package br.com.gestorcobranca.compliance.domain.model;

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
 * Tentativa de trazer de volta o titular eliminado a pedido (SEG-20261124-D01h, decisão 1471): a carga ou o cadastro
 * trouxe o documento e foi recusado. Fica para revisão de quem atende titulares, que mantém a recusa ou libera o
 * retorno (decisão 1604). Sem dado pessoal: o pedido, o credor, a origem, o lote e quantas linhas.
 */
@Entity
@Table(name = "erased_subject_return", schema = "compliance")
public class ErasedSubjectReturn extends TenantScopedEntity {

    /** De onde veio a tentativa (os mesmos valores da SPI do debt). */
    public enum Source { IMPORT, DEBTOR_FORM, CO_OBLIGOR_FORM }

    /** Resultado da revisão humana. */
    public enum Outcome { KEPT, RELEASED }

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, updatable = false)
    private Source source;

    @Column(name = "batch_id", updatable = false)
    private UUID batchId;

    @Column(name = "lines", nullable = false, updatable = false)
    private int lines;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20)
    private Outcome outcome;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    protected ErasedSubjectReturn() {
    }

    public ErasedSubjectReturn(UUID requestId, UUID creditorId, Source source, UUID batchId, int lines,
                               Instant attemptedAt) {
        Guard.require(requestId != null && source != null && attemptedAt != null, "erased_subject_return.invalid",
                "Tentativa de retorno incompleta");
        Guard.require((source == Source.IMPORT) == (batchId != null), "erased_subject_return.batch_invalid",
                "Lote só na carga");
        Guard.require(lines > 0, "erased_subject_return.lines_invalid", "Quantidade de linhas inválida");
        this.requestId = requestId;
        this.creditorId = creditorId;
        this.source = source;
        this.batchId = batchId;
        this.lines = lines;
        this.attemptedAt = attemptedAt;
    }

    /** Revisão feita uma vez só. */
    public void review(Outcome result, UUID reviewer, Instant now) {
        Guard.requireState(outcome == null, "erased_subject_return.already_reviewed", "Tentativa já revisada");
        Guard.require(result != null && reviewer != null && now != null, "erased_subject_return.review_invalid",
                "Revisão incompleta");
        this.outcome = result;
        this.reviewedBy = reviewer;
        this.reviewedAt = now;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public Source getSource() {
        return source;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public int getLines() {
        return lines;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }
}
