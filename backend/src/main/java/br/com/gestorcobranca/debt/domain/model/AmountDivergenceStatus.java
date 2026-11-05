package br.com.gestorcobranca.debt.domain.model;

/** Situação da divergência entre o principal corrigido pela assessoria e o informado pelo credor numa carga. */
public enum AmountDivergenceStatus {
    OPEN,
    /** Mantida a correção da assessoria; o valor do credor fica reconhecido e não reabre a divergência. */
    KEPT_MANUAL,
    /** Aceito o valor do credor: lançado no razão e a correção manual deixa de valer. */
    ACCEPTED_CREDITOR,
    /** A carga seguinte trouxe o mesmo valor da correção. */
    RESOLVED_BY_CREDITOR,
    /** Nova carga com outro valor substituiu esta divergência. */
    SUPERSEDED
}
