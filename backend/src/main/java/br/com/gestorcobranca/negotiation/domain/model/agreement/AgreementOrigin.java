package br.com.gestorcobranca.negotiation.domain.model.agreement;

/** Origem do acordo para a atribuição de crédito (ver {@code docs/tecnico/desenho/remuneracao-variavel.md} §3.2). */
public enum AgreementOrigin {
    HUMAN,
    HUMAN_COPILOT,
    ASSISTANT_HANDOFF_NEGOTIATED,
    ASSISTANT_HANDOFF_CONFIRMED,
    ASSISTANT_AUTONOMOUS,
    PORTAL_SELF_SERVICE,
    CAMPAIGN
}
