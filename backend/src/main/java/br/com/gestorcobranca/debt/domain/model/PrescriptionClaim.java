package br.com.gestorcobranca.debt.domain.model;

/**
 * Pretensão cujo prazo de prescrição é acompanhado. Um mesmo título pode ter prazos diferentes
 * para cada via: o cheque, por exemplo, perde a força executiva em meses, mas a dívida continua
 * cobrável por ação de cobrança ou monitória por anos.
 */
public enum PrescriptionClaim {
    /** Pretensão executiva (execução do título executivo extrajudicial). Só se aplica a contrato marcado como título executivo. */
    EXECUTION,
    /** Pretensão de cobrança (ação de cobrança, monitória): limite da cobrança da dívida. */
    COLLECTION
}
