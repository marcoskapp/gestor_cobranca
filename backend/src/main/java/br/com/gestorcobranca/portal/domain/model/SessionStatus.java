package br.com.gestorcobranca.portal.domain.model;

/** {@code ENDED}: saída do devedor; {@code REVOKED}: gestão/segurança; {@code EXPIRED}: inatividade ou prazo. */
public enum SessionStatus { ACTIVE, ENDED, REVOKED, EXPIRED }
