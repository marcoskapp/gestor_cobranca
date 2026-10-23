package br.com.gestorcobranca.negotiation.domain.model.calculation;

/**
 * Componentes de uma composição de dívida. A correção monetária é discriminada para
 * transparência da memória de cálculo; no razão do título ({@code debt}) ela é reconhecida como
 * encargo ({@code FEES}) — ver {@link #ledgerComponent()}.
 */
public enum DebtComponent {
    PRINCIPAL,
    /** Correção monetária pelo índice da política. */
    CORRECTION,
    /** Juros de mora. */
    INTEREST,
    /** Multa moratória. */
    FINE,
    /** Encargos autorizados pelo credor. */
    FEES,
    /** Honorários da assessoria. */
    HONORARIUM;

    /** Componente correspondente no razão do título do módulo debt. */
    public String ledgerComponent() {
        return this == CORRECTION ? "FEES" : name();
    }
}
