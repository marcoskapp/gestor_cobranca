package br.com.gestorcobranca.debt.domain.model;

/** Papel de uma pessoa na obrigação de um contrato de origem. */
public enum ObligationRole {
    /** Devedor principal: o devedor do contrato/título ({@code OriginContract.debtorId}); não é cadastrado como parte. */
    PRINCIPAL,
    /** Avalista (garantia cambial em título de crédito). */
    GUARANTOR_AVAL,
    /** Fiador. */
    GUARANTOR_SURETY,
    /** Codevedor solidário. */
    CO_DEBTOR,
    /** Terceiro que ofereceu bem próprio em garantia. */
    THIRD_PARTY_COLLATERAL_PROVIDER
}
