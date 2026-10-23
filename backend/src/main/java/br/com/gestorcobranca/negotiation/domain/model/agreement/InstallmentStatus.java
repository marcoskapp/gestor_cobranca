package br.com.gestorcobranca.negotiation.domain.model.agreement;

/**
 * Situação da parcela do acordo, recalculada a cada pagamento, estorno, varredura diária e
 * encerramento do acordo:
 * <ol>
 *   <li>pago ≥ valor → {@link #PAID};</li>
 *   <li>acordo encerrado (quebra, cancelamento, renegociação) → {@link #CANCELLED};</li>
 *   <li>vencimento anterior a hoje → {@link #OVERDUE} (inclusive se parcialmente paga);</li>
 *   <li>pagamento parcial → {@link #PARTIALLY_PAID};</li>
 *   <li>senão {@link #OPEN}.</li>
 * </ol>
 */
public enum InstallmentStatus {
    OPEN, PAID, PARTIALLY_PAID, OVERDUE, CANCELLED
}
