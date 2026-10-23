package br.com.gestorcobranca.compliance.domain.model;

/**
 * Situação da restrição. Uma restrição temporária continua {@code ACTIVE} depois do fim da
 * vigência: ela simplesmente deixa de produzir efeito ({@link ContactRestriction#isEffectiveAt}).
 */
public enum RestrictionStatus {
    ACTIVE,
    REVOKED
}
