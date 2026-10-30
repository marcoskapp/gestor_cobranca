package br.com.gestorcobranca.publicapi.domain.model;

/** Situação efetiva do token num instante: ativo, revogado ou expirado. */
public enum TokenState {
    ACTIVE,
    REVOKED,
    EXPIRED
}
