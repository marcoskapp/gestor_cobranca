package br.com.gestorcobranca.negotiation.domain.model.agreement;

/** Por que a suspensão da quebra terminou. */
public enum BreakSuspensionEndReason {
    /** Todos os pagamentos em revisão foram decididos e ao menos um foi aprovado (baixa normal da parcela). */
    APPROVED,
    /** Todos os pagamentos em revisão foram recusados: a quebra volta a valer depois da carência. */
    REJECTED,
    /** Prazo máximo de suspensão vencido com pagamento ainda em revisão: a quebra volta a valer. */
    DEADLINE,
    /** O acordo deixou de estar em vigor (quitado, cancelado, renegociado ou quebrado). */
    AGREEMENT_ENDED;

    /** Depois deste fim, a mesma parcela não suspende a quebra de novo (decisão 544). */
    public boolean blocksNewSuspension() {
        return this == REJECTED || this == DEADLINE;
    }
}
