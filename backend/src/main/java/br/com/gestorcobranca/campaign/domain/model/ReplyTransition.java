package br.com.gestorcobranca.campaign.domain.model;

/**
 * O que a resposta do devedor faz com a sequência (RB07): encerrar, ir para uma etapa, entregar ao
 * assistente, mover para a fila (humanos) ou seguir normalmente.
 */
@org.springframework.modulith.NamedInterface("usecases")
public enum ReplyTransition {
    STOP,
    GOTO_STEP,
    HAND_TO_ASSISTANT,
    MOVE_TO_QUEUE,
    CONTINUE
}
