package br.com.gestorcobranca.legal.domain.model;

/** Situação do prazo: pendente, cumprido, perdido (vencido sem cumprimento) ou cancelado. */
public enum DeadlineStatus {
    PENDING,
    MET,
    MISSED,
    CANCELLED
}
