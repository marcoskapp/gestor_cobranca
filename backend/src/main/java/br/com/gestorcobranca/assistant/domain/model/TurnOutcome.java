package br.com.gestorcobranca.assistant.domain.model;

/** Resultado de um turno do assistente. */
public enum TurnOutcome {
    /** resposta enviada ao devedor (pelo porteiro) */
    REPLIED,
    /** rascunho/sugestão criado para o operador (sombra/copiloto) */
    DRAFTED,
    /** conversa transferida para humanos */
    HANDED_OFF,
    /** aceite registrado; formalização aguardando humano (modo com aprovação ou perfil sem formalização) */
    APPROVAL_REQUESTED,
    /** resposta negada pelo porteiro de contato (nada foi enviado) */
    SEND_BLOCKED,
    /** resposta recusada pelo guardrail no modo de rascunho (nada é sugerido) */
    GUARDRAIL_BLOCKED,
    /** sem ação (ex.: retomado sem mensagens novas) */
    SKIPPED,
    /** falha técnica; conversa transferida para humanos */
    FAILED
}
