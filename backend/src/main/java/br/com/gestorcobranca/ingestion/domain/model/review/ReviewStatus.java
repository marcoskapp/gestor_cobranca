package br.com.gestorcobranca.ingestion.domain.model.review;

/**
 * Ciclo de vida da conferência ({@code importacao.md} §5.1).
 * <pre>
 * OPEN → COMMITTED | AWAITING_APPROVAL | CANCELLED
 * AWAITING_APPROVAL → COMMITTED | REJECTED
 * </pre>
 */
public enum ReviewStatus {
    OPEN,
    AWAITING_APPROVAL,
    COMMITTED,
    REJECTED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMMITTED || this == REJECTED || this == CANCELLED;
    }
}
