package br.com.gestorcobranca.publicapi.domain.model;

/**
 * Situação efetiva do token num instante: ativo, pausado pela plataforma (reversível), revogado ou expirado
 * (definitivos). Revogado e expirado prevalecem sobre a pausa.
 */
public enum TokenState {
    ACTIVE,
    PAUSED,
    REVOKED,
    EXPIRED
}
