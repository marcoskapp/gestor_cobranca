package br.com.gestorcobranca.collection.domain.model;

/** Como o responsável da contestação mudou: atribuição manual, "pegar para mim", distribuição automática, redistribuição ou devolução (sem responsável). */
public enum DisputeAssignmentMode {
    MANUAL,
    TAKE,
    AUTOMATIC,
    REDISTRIBUTION,
    RELEASE
}
