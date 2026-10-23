package br.com.gestorcobranca.legal.domain.model;

/** Meio pelo qual o credor formalizou a decisão (a evidência é anexada ao registro). */
public enum AuthorizationChannel {
    EMAIL,
    SIGNED_DOCUMENT,
    CREDITOR_PORTAL,
    LETTER,
    OTHER
}
