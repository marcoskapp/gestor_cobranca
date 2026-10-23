package br.com.gestorcobranca.payment.domain.model;

/**
 * Situação da instrução: {@code PENDING} (aguardando registro no provedor) → {@code ACTIVE} →
 * {@code PAID | EXPIRED | CANCELLED}; recusa definitiva do provedor → {@code FAILED}.
 */
public enum InstructionStatus {
    PENDING, ACTIVE, PAID, EXPIRED, CANCELLED, FAILED;

    public boolean isOpen() {
        return this == PENDING || this == ACTIVE;
    }
}
