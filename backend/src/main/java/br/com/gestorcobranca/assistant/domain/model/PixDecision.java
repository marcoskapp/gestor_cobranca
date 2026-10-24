package br.com.gestorcobranca.assistant.domain.model;

/** Resultado da avaliação de um pedido de Pix na conversa. */
public enum PixDecision {
    /** Pix gerado pelo assistente (modos autônomos) */
    ISSUED,
    /** algum critério falhou: nada gerado */
    DENIED,
    /** modos sombra/copiloto: critérios atendidos, sugestão ao operador (que gera pela tela) */
    SUGGESTED
}
