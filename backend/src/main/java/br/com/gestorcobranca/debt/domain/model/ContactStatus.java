package br.com.gestorcobranca.debt.domain.model;

/** Qualidade do contato: contatos inválidos não são usados por réguas e discador. */
public enum ContactStatus {
    UNVERIFIED,
    VALID,
    INVALID;

    public boolean isUsable() {
        return this != INVALID;
    }
}
