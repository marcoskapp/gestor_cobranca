package br.com.gestorcobranca.communication.domain.model;

/** Modo do assistente que gerou o rascunho (assistente.md §4). */
public enum DraftMode {
    /** Rascunho no atendimento do operador; nada é enviado sem humano. */
    SHADOW,
    /** Sugestão de texto/oferta ao operador. */
    COPILOT
}
