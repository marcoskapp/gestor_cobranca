package br.com.gestorcobranca.portal.domain.model;

/** Pedidos que fogem do portal e vão para a fila humana. */
public enum ServiceRequestReason {
    /** Falar com um atendente. */
    TALK_TO_AGENT,
    /**
     * Contestar a dívida (não reconhece, valor incorreto, já pagou): registrada automaticamente como contestação no
     * collection (job {@code portal.dispute_intake}; o prazo do CDC conta do pedido) e também levada à fila humana.
     */
    DISPUTE,
    /** Nenhuma condição do portal cabe no orçamento. */
    CANNOT_AFFORD,
    OTHER;

    /** Motivo estável do {@code HandoffRequested} (contrato do communication). */
    public String handoffReason() {
        return switch (this) {
            case DISPUTE -> "DISPUTE";
            case CANNOT_AFFORD -> "PORTAL_NEGOTIATION";
            default -> "DEBTOR_REQUESTED_HUMAN";
        };
    }
}
