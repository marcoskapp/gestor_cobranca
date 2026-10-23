package br.com.gestorcobranca.telephony.domain.model;

/** Situação do registro da chamada como interação no caso (collection). */
public enum InteractionState {
    /** Nada a registrar ainda (em andamento). */
    NONE,
    /** Aguardando tabulação do operador (pós-atendimento). */
    AWAITING_TABULATION,
    /** Tabulada (operador ou automática); registro no caso pendente ou concluído. */
    PENDING,
    RECORDED,
    /** Não registrada: sem caso, caso encerrado, chamada nunca discada ou tabulação indisponível. */
    SKIPPED
}
