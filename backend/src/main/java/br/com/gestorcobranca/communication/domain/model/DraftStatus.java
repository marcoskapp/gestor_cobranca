package br.com.gestorcobranca.communication.domain.model;

/** Situação de um rascunho do assistente. */
public enum DraftStatus {
    PENDING,
    SENT,
    DISCARDED,
    /** A conversa foi encerrada ou mudou de dono antes da decisão. */
    EXPIRED
}
