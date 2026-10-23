package br.com.gestorcobranca.legal.domain.model;

/** Autorização do credor: solicitada, concedida, negada ou substituída por uma concessão posterior. */
public enum AuthorizationStatus {
    REQUESTED,
    GRANTED,
    DENIED,
    SUPERSEDED
}
