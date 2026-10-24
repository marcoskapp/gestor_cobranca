package br.com.gestorcobranca.scoring.domain.model;

/** Base legal do tratamento para o score (LGPD art. 7º). */
public enum LegalBasis {
    /** Art. 7º, IX — legítimo interesse do controlador (exige avaliação de legítimo interesse registrada). */
    LEGITIMATE_INTEREST,
    /** Art. 7º, X — proteção do crédito. */
    CREDIT_PROTECTION
}
