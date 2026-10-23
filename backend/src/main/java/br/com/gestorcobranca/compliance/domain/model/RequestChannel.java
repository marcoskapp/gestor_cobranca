package br.com.gestorcobranca.compliance.domain.model;

/** Canal pelo qual a solicitação do titular foi recebida. */
public enum RequestChannel {
    EMAIL,
    PHONE,
    WHATSAPP,
    PORTAL,
    LETTER,
    IN_PERSON,
    /** Encaminhada pelo credor (controlador). */
    CREDITOR_FORWARD
}
