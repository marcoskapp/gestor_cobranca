package br.com.gestorcobranca.negotiation.domain.service;

import br.com.gestorcobranca.negotiation.domain.model.agreement.BreakSuspensionEndReason;

/**
 * Regras puras da suspensão da quebra por pagamento em revisão (decisões 540–549). O serviço reúne os
 * fatos (suspensão em vigor, pagamentos em revisão e em trânsito na fonte, carência, uso anterior da
 * parcela) e aplica estas decisões com o acordo bloqueado.
 */
public final class BreakHoldRules {

    private BreakHoldRules() {
    }

    /** Resultado da avaliação de um acordo que atingiu a regra de quebra. */
    public enum Outcome {
        /** Quebra normal (nada a esperar). */
        BREAK,
        /** Há pagamento em revisão, mas a parcela já teve suspensão encerrada por recusa ou prazo: quebra. */
        BREAK_SUSPENSION_USED,
        /** Começa uma suspensão agora. */
        SUSPEND,
        /** Suspensão em vigor (revisão pendente dentro do prazo). */
        HOLD_SUSPENDED,
        /** Recusa recente: carência em curso. */
        HOLD_GRACE,
        /** Pagamento já confirmado e ainda não baixado (evento em consumo): espera a próxima execução. */
        DEFER_IN_TRANSIT;

        public boolean allowsBreak() {
            return this == BREAK || this == BREAK_SUSPENSION_USED;
        }
    }

    /**
     * @param activeSuspension suspensão ainda em vigor depois de {@link #reconcile}
     * @param pendingReview    há externo em revisão com o acordo como destino
     * @param inTransit        há alocação confirmada ao acordo ainda não baixada
     * @param inGrace          a última suspensão terminou por recusa e a carência não passou
     * @param triggerBlocked   a parcela que dispara a quebra já teve suspensão encerrada por recusa ou prazo
     */
    public static Outcome decide(boolean activeSuspension, boolean pendingReview, boolean inTransit, boolean inGrace,
                                 boolean triggerBlocked) {
        if (activeSuspension) {
            return Outcome.HOLD_SUSPENDED;
        }
        if (inTransit) {
            return Outcome.DEFER_IN_TRANSIT;
        }
        if (inGrace) {
            return Outcome.HOLD_GRACE;
        }
        if (pendingReview) {
            return triggerBlocked ? Outcome.BREAK_SUSPENSION_USED : Outcome.SUSPEND;
        }
        return Outcome.BREAK;
    }

    /**
     * Como fica uma suspensão em vigor diante da situação atual. Nulo = continua.
     *
     * @param agreementActive acordo ainda em vigor
     * @param pendingReview   ainda há externo em revisão com o acordo como destino
     * @param anyApproved     algum dos revisados desde o início da suspensão foi aprovado
     * @param deadlinePassed  o prazo máximo venceu no dia de referência
     */
    public static BreakSuspensionEndReason reconcile(boolean agreementActive, boolean pendingReview, boolean anyApproved,
                                                     boolean deadlinePassed) {
        if (!agreementActive) {
            return BreakSuspensionEndReason.AGREEMENT_ENDED;
        }
        if (!pendingReview) {
            // revisão concluída; sem aprovado (ou sem registro de revisão) vale a regra da recusa, com carência
            return anyApproved ? BreakSuspensionEndReason.APPROVED : BreakSuspensionEndReason.REJECTED;
        }
        return deadlinePassed ? BreakSuspensionEndReason.DEADLINE : null;
    }
}
