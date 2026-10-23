package br.com.gestorcobranca.campaign.domain.model;

/** Motivo do encerramento de uma inscrição. */
public enum EndReason {
    /** Todas as etapas percorridas sem resposta, acordo ou pagamento: régua esgotada. */
    CADENCE_EXHAUSTED,
    /** Etapas percorridas com resultado (resposta, contato efetivo, acordo ou pagamento). */
    CADENCE_COMPLETED,
    /** Resposta do devedor com transição "encerrar" (RB07). */
    REPLIED,
    HANDED_TO_ASSISTANT,
    MOVED_TO_QUEUE,
    /** Caso encerrado no collection. */
    CASE_CLOSED,
    /** O caso deixou de atender ao segmento ({@code exitWhenOutOfSegment}). */
    LEFT_SEGMENT,
    /** Substituída por campanha de prioridade maior do mesmo grupo de exclusividade. */
    PREEMPTED,
    CAMPAIGN_FINISHED,
    /** Fim do horizonte de observação do grupo de controle. */
    HOLDOUT_FINISHED,
    MANUAL
}
