package br.com.gestorcobranca.complaint.domain.model;

/**
 * {@code RECEIVED → IN_PROGRESS → ANSWERED → CLOSED}; encerrar também direto de {@code ANSWERED}. O prazo de resposta
 * vale enquanto {@code RECEIVED} ou {@code IN_PROGRESS}.
 */
public enum ComplaintStatus {
    RECEIVED,
    IN_PROGRESS,
    /** Resposta enviada ao canal (aguardando avaliação ou retorno do consumidor). */
    ANSWERED,
    CLOSED;

    public boolean awaitingAnswer() {
        return this == RECEIVED || this == IN_PROGRESS;
    }
}
