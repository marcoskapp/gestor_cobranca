package br.com.gestorcobranca.publicapi.domain.model;

/** Situação persistida do token. "Expirado" é derivado da data de expiração (ver {@link IntegrationToken#stateAt}). */
public enum TokenStatus {
    ACTIVE,
    REVOKED
}
