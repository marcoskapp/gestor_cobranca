package br.com.gestorcobranca.identity.domain.model.creditor;

/** Situação da conta da pessoa do credor. */
public enum CreditorUserStatus {
    /** Convidada: sem senha nem MFA; a ativação define os dois numa transação. */
    INVITED,
    /** Ativa: senha e MFA cadastrados. */
    ACTIVE,
    /** Revogada pela assessoria: nenhuma sessão, nenhum link. */
    REVOKED
}
