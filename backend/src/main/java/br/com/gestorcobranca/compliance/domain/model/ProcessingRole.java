package br.com.gestorcobranca.compliance.domain.model;

/** Papel da assessoria no tratamento dos dados de um credor (LGPD art. 5º, VI e VII). */
public enum ProcessingRole {
    /** Trata dados em nome do credor (controlador), conforme suas instruções — caso usual. */
    OPERADOR,
    /** Decide sobre o tratamento (ex.: carteira adquirida). */
    CONTROLADOR,
    /** Decide em conjunto com o credor. */
    CO_CONTROLADOR
}
