package br.com.gestorcobranca.assistant.domain.model;

/** Confirmação de identidade do interlocutor antes de revelar a dívida. */
public enum IdentityStatus {
    PENDING,
    VERIFIED,
    FAILED,
    /** a política não exige (ex.: canal já autenticado) */
    NOT_REQUIRED
}
