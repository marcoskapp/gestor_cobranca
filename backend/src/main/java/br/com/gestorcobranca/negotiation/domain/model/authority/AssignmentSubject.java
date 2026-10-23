package br.com.gestorcobranca.negotiation.domain.model.authority;

/** Quem recebe um perfil de alçada. */
public enum AssignmentSubject {
    /** Usuário humano (id do usuário do identity). */
    USER,
    /**
     * Assistente de IA operando sob uma política do módulo assistant (id da {@code AssistantPolicy},
     * que é o id do {@code Actor.assistant}).
     */
    ASSISTANT_POLICY
}
