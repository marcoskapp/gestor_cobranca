package br.com.gestorcobranca.incentive.domain.model.attribution;

/** Origem do acordo para fins de crédito (§3.2). */
public enum AgreementOrigin {
    /** Humano negociou e fechou. */
    HUMAN,
    /** Humano fechou usando sugestões do assistente (copiloto): o assistente é ferramenta. */
    HUMAN_COPILOT,
    /** Assistente transferiu e o humano negociou de fato. */
    ASSISTANT_HANDOFF_NEGOTIATED,
    /** Assistente deixou a proposta pronta e o humano só confirmou. */
    ASSISTANT_HANDOFF_CONFIRMED,
    /** Assistente fechou sozinho: sem crédito individual, entra no pool da equipe. */
    ASSISTANT_AUTONOMOUS,
    /** Devedor fechou no portal: crédito ao influenciador humano, se houver. */
    PORTAL_SELF_SERVICE,
    /** Fechamento por régua automatizada: tratado como o portal. */
    CAMPAIGN;

    /** Origens em que o crédito individual é do closer humano. */
    public boolean creditsCloser() {
        return this == HUMAN || this == HUMAN_COPILOT || this == ASSISTANT_HANDOFF_NEGOTIATED
                || this == ASSISTANT_HANDOFF_CONFIRMED;
    }

    /** Origens em que o crédito individual é do influenciador (janela de influência). */
    public boolean creditsInfluencer() {
        return this == PORTAL_SELF_SERVICE || this == CAMPAIGN;
    }
}
