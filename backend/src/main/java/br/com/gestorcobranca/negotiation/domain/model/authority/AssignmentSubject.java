package br.com.gestorcobranca.negotiation.domain.model.authority;

/** Quem recebe um perfil de alçada. */
public enum AssignmentSubject {
    /** Usuário humano (id do usuário do identity). */
    USER,
    /**
     * Assistente de IA operando sob uma política do módulo assistant (id da {@code AssistantPolicy},
     * que é o id do {@code Actor.assistant}).
     */
    ASSISTANT_POLICY,
    /**
     * Canal de autonegociação (portal do devedor): o ator é o devedor ({@code Actor.ActorType.DEBTOR}),
     * mas a alçada é do canal. O sujeito tem um único id, {@link #PORTAL_CHANNEL_ID}; a atribuição por
     * credor permite um perfil próprio de autonegociação por credor (padrão: {@code PORTAL}).
     */
    PORTAL;

    /** Id único do sujeito {@link #PORTAL} (o canal, não o devedor). */
    public static final java.util.UUID PORTAL_CHANNEL_ID = new java.util.UUID(0L, 1L);
}
