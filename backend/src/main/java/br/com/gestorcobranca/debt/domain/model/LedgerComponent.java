package br.com.gestorcobranca.debt.domain.model;

/** Componentes da dívida discriminados no razão do título (RB03). */
public enum LedgerComponent {
    PRINCIPAL,
    /** Juros (remuneratórios/moratórios) reconhecidos. */
    INTEREST,
    /** Multa contratual. */
    FINE,
    /** Encargos autorizados pelo credor (tarifas, correção). */
    FEES,
    /** Honorários da assessoria. */
    HONORARIUM
}
