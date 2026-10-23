package br.com.gestorcobranca.legal.domain.model;

/** Situação do processo: em andamento, suspenso, arquivado (provisoriamente) ou extinto (definitivamente). */
public enum LawsuitStatus {
    ACTIVE,
    SUSPENDED,
    ARCHIVED,
    TERMINATED;

    public boolean canMoveTo(LawsuitStatus target) {
        return this != TERMINATED && target != this;
    }
}
