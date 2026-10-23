package br.com.gestorcobranca.payment.domain.model;

/**
 * Resultado da baixa informado pelo negotiation ({@code AgreementPaymentRecorded}) para alocações a
 * acordo: {@code PENDING} até o retorno; {@code APPLIED} integral; {@code PARTIAL} com excedente não
 * aplicado; {@code UNAPPLIED} (acordo encerrado). Títulos e devolução: {@code NOT_APPLICABLE}.
 */
public enum AllocationOutcome {
    PENDING, APPLIED, PARTIAL, UNAPPLIED, NOT_APPLICABLE
}
