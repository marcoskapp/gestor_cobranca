package br.com.gestorcobranca.debt.domain.model;

/** Papel do representante da empresa devedora (B3). */
public enum RepresentativeRole {
    /** Sócio administrador (contrato social). */
    MANAGING_PARTNER,
    /** Procurador (procuração). */
    ATTORNEY_IN_FACT,
    OTHER
}
