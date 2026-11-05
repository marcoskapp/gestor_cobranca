package br.com.gestorcobranca.collection.domain.model;

/** Posição do credor na resposta: confirma a dívida, reconhece a alegação do devedor, reconhece em parte ou só informa (documentos, esclarecimentos). */
public enum CreditorPosition {
    CONFIRMS_DEBT,
    ACCEPTS_CLAIM,
    PARTIAL,
    INFORMATION
}
