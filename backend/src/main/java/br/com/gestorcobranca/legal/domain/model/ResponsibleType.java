package br.com.gestorcobranca.legal.domain.model;

/** Quem conduz o caso jurídico: ninguém ainda, o jurídico interno (usuário) ou um escritório parceiro. */
public enum ResponsibleType {
    NONE,
    INTERNAL,
    LAW_FIRM
}
