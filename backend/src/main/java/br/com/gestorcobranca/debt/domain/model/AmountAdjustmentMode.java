package br.com.gestorcobranca.debt.domain.model;

/** Como a correção foi autorizada. */
public enum AmountAdjustmentMode {
    /** Redução até o limite da assessoria: aplicada no registro por quem tem {@code TITLE_AMOUNT_ADJUST}. */
    WITHIN_LIMIT,
    /** Redução acima do limite ou qualquer aumento: segunda pessoa aprova (RB06), com evidência. */
    DUAL_CONTROL
}
