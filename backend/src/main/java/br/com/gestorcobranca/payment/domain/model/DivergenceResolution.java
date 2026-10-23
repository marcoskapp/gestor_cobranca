package br.com.gestorcobranca.payment.domain.model;

/** Resolução: vinculada a um pagamento, aceita como está ou descartada. */
public enum DivergenceResolution {
    MATCHED_PAYMENT, ACCEPTED, DISMISSED
}
