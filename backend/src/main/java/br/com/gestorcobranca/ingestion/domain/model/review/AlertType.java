package br.com.gestorcobranca.ingestion.domain.model.review;

/**
 * Tipos de alerta de valor da conferência ({@code importacao.md} §4), com a prioridade base na lista. Alertas de
 * <b>escala</b> indicam provável erro de separador decimal ou de unidade e precisam ser todos revisados.
 */
public enum AlertType {
    DECIMAL_SCALE(100, true),
    FILE_SCALE_SHIFT(95, true),
    CENTS_PATTERN(90, true),
    AMOUNT_HIGH(70, false),
    BALANCE_ABOVE_PRINCIPAL(60, false),
    CONTRACT_INSTALLMENTS_MISMATCH(55, false),
    DEBTOR_MAGNITUDE_SPREAD(50, false),
    AMOUNT_LOW(40, false),
    DUE_DATE_TOO_FUTURE(30, false),
    DUE_DATE_TOO_OLD(30, false);

    private final int priority;
    private final boolean scale;

    AlertType(int priority, boolean scale) {
        this.priority = priority;
        this.scale = scale;
    }

    public int priority() {
        return priority;
    }

    public boolean scale() {
        return scale;
    }
}
