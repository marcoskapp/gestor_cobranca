package br.com.gestorcobranca.collection.domain.model;

/** Quem registrou a interação. */
public enum InteractionSource {
    /** Operador, na tela de atendimento. */
    MANUAL,
    /** Integração de telefonia (CTI/discador). */
    TELEPHONY,
    /** Módulo de comunicação (mensagens). */
    COMMUNICATION,
    /** Assistente de IA. */
    ASSISTANT,
    /** Processo automático. */
    SYSTEM
}
