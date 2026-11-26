package br.com.gestorcobranca.debt.domain.model;

/**
 * Motivo codificado da suspensão do título pela carga do credor (H07 2b; {@code check} na {@code V0314}). As demais
 * suspensões ficam só com o motivo em texto.
 */
public enum TitleSuspensionReason {
    /** O credor informou na carga que o título foi pago (aguardando conciliação). */
    CREDITOR_REPORTED_PAID,
    /** O credor informou na carga que o título foi cancelado. */
    CREDITOR_REPORTED_CANCELLED
}
