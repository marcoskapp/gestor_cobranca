package br.com.gestorcobranca.debt.domain.model;

/** Motivo do encerramento de um período de titularidade. */
public enum OwnershipReleaseType {
    /** Título movido para outra carteira do mesmo credor. */
    TRANSFER,
    /** Título devolvido ao credor. */
    RETURN
}
