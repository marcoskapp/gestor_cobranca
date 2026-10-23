package br.com.gestorcobranca.communication.domain.model;

/**
 * Situação de uma mensagem. Saída: {@code QUEUED → SENT → DELIVERED → READ}, com {@code FAILED}
 * a partir de {@code QUEUED} ou {@code SENT}; nunca regride (webhooks fora de ordem são
 * absorvidos). Entrada: {@code RECEIVED}.
 */
public enum MessageStatus {
    QUEUED(0),
    SENT(1),
    DELIVERED(2),
    READ(3),
    FAILED(9),
    RECEIVED(0);

    private final int rank;

    MessageStatus(int rank) {
        this.rank = rank;
    }

    /** Posição na progressão de entrega (FAILED é final). */
    public int rank() {
        return rank;
    }

    public boolean isFinal() {
        return this == READ || this == FAILED || this == RECEIVED;
    }
}
