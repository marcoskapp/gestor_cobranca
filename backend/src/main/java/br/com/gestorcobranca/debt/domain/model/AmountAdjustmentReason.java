package br.com.gestorcobranca.debt.domain.model;

/** Motivo da correção manual do valor do título (lista fechada; o texto livre detalha). */
public enum AmountAdjustmentReason {
    /** Erro de cálculo ou de digitação no valor enviado. */
    CALCULATION_ERROR,
    /** O credor confirmou o valor correto (por escrito, fora da carga). */
    CREDITOR_CONFIRMATION,
    /** Contestação procedente ou parcialmente procedente. */
    DISPUTE_UPHELD,
    /** Cobrança em duplicidade de encargo ou parcela. */
    DUPLICATE_CHARGE,
    /** Cláusula do contrato (juros, multa ou encargo acima do contratado). */
    CONTRACT_TERMS,
    /** Dispensa parcial autorizada pelo credor fora de negociação. */
    PARTIAL_WAIVER,
    OTHER
}
