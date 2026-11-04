package br.com.gestorcobranca.publicapi.domain.model;

/**
 * Situação persistida do token. "Expirado" é derivado da data de expiração e a retomada automática de uma pausa vale
 * a partir de {@code resumeAt} mesmo antes de gravada (ver {@link IntegrationToken#stateAt}).
 */
public enum TokenStatus {
    ACTIVE,
    /** Pausado pela plataforma (reversível; só a plataforma retoma). */
    PAUSED,
    REVOKED
}
