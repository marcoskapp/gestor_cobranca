package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Money;

/**
 * Natureza de um lançamento no razão do título. Convenção de sinal do valor:
 * <ul>
 *   <li><b>débito ao devedor</b> (positivo, aumenta o saldo): {@link #OPENING}, {@link #CHARGE},
 *       {@link #REVERSAL};</li>
 *   <li><b>crédito ao devedor</b> (negativo, reduz o saldo): {@link #PAYMENT}, {@link #DISCOUNT},
 *       {@link #WRITE_OFF};</li>
 *   <li>{@link #ADJUSTMENT}: sinal da correção (positivo ou negativo, nunca zero).</li>
 * </ul>
 * O saldo de um componente é a soma dos seus lançamentos.
 */
public enum LedgerEntryType {
    /** Valor original do título na criação. */
    OPENING,
    /** Juros, multa, encargos ou honorários reconhecidos (ex.: num acordo). */
    CHARGE,
    /** Correção do principal informada pelo credor em nova carga. */
    ADJUSTMENT,
    /** Desconto concedido numa negociação. */
    DISCOUNT,
    /** Baixa do saldo remanescente (perdão) na quitação de acordo. */
    WRITE_OFF,
    PAYMENT,
    /** Estorno de pagamento: lançamento compensatório que espelha o pagamento original. */
    REVERSAL;

    public boolean acceptsAmount(Money amount) {
        return switch (this) {
            case OPENING, CHARGE, REVERSAL -> amount.isPositive();
            case PAYMENT, DISCOUNT, WRITE_OFF -> amount.isNegative();
            case ADJUSTMENT -> !amount.isZero();
        };
    }
}
