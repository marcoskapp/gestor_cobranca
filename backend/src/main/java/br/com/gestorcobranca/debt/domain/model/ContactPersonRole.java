package br.com.gestorcobranca.debt.domain.model;

/**
 * Papel da pessoa de contato (cobrança B2B, B2): com quem se fala naquele telefone ou e-mail. Também vale para pessoa
 * física (ex.: recado com um parente → {@link #OTHER}).
 */
public enum ContactPersonRole {
    /** Financeiro. */
    FINANCE,
    /** Contas a pagar. */
    ACCOUNTS_PAYABLE,
    /** Sócio ou proprietário. */
    OWNER_PARTNER,
    /** Jurídico. */
    LEGAL,
    OTHER
}
