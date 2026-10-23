package br.com.gestorcobranca.payment.domain.model;

/** Motivo do estorno. */
public enum ReversalReason {
    PROVIDER_REFUND, CHARGEBACK, BANK_RETURN, REGISTRATION_ERROR, FRAUD, OTHER
}
