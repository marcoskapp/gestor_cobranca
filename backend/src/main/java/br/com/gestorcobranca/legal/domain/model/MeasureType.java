package br.com.gestorcobranca.legal.domain.model;

/**
 * Medidas extrajudiciais (etapa 4) e a via que precisa estar autorizada pelo credor.
 * {@code NOTICE} (notificação e constituição em mora) é preparatória e vale com qualquer via
 * autorizada.
 */
public enum MeasureType {
    NOTICE(null),
    PROTEST(LegalRoute.PROTEST),
    CREDIT_BUREAU_LISTING(LegalRoute.CREDIT_BUREAU_LISTING),
    COLLATERAL_ENFORCEMENT(LegalRoute.EXTRAJUDICIAL_COLLATERAL_ENFORCEMENT);

    private final LegalRoute route;

    MeasureType(LegalRoute route) {
        this.route = route;
    }

    /** Via exigida na autorização; nula = qualquer via autorizada. */
    public LegalRoute route() {
        return route;
    }

    /** Medidas que geram restrição a ser baixada após o pagamento (prazo de baixa). */
    public boolean requiresRemovalAfterPayment() {
        return this == PROTEST || this == CREDIT_BUREAU_LISTING;
    }
}
