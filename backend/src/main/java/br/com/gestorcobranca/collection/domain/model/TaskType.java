package br.com.gestorcobranca.collection.domain.model;

/** Tipo de tarefa do caso. */
public enum TaskType {
    /** Retorno ao devedor em data e hora combinadas. */
    CALLBACK,
    /** Acompanhamento genérico (ex.: conferir documento enviado). */
    FOLLOW_UP
}
