package br.com.gestorcobranca.assistant.domain.model;

/** Sessão do assistente numa conversa: conduzindo ou já transferida para humanos. */
public enum SessionStatus {
    ACTIVE,
    HANDED_OFF
}
