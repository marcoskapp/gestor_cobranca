package br.com.gestorcobranca.payment.domain.model;

/**
 * Ciclo de vida do pagamento (RB06): {@code INFORMED → CONFIRMED → RECONCILED}; informado recusado
 * na revisão → {@code REJECTED}; confirmado ou conciliado estornado → {@code REVERSED} (evento
 * compensatório; o registro nunca é apagado). Só confirmados e conciliados contam como recuperação.
 */
public enum PaymentStatus {
    INFORMED, CONFIRMED, RECONCILED, REJECTED, REVERSED;

    public boolean isEffective() {
        return this == CONFIRMED || this == RECONCILED;
    }
}
