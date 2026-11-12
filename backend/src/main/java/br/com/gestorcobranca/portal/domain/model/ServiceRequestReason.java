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
    OTHER,
    /**
     * Devedor pessoa jurídica escolheu uma condição, mas quem negocia não foi confirmado como representante com poder
     * para negociar (ou a empresa não tem representante cadastrado): um atendente conclui o acordo (B4). Criado pelo
     * próprio portal no aceite, nunca escolhido pelo devedor.
     */
    COMPANY_ACCEPTANCE;

    /** Motivo estável do {@code HandoffRequested} (contrato do communication). */
    public String handoffReason() {
        return switch (this) {
            case DISPUTE -> "DISPUTE";
            case CANNOT_AFFORD, COMPANY_ACCEPTANCE -> "PORTAL_NEGOTIATION";
            default -> "DEBTOR_REQUESTED_HUMAN";
        };
    }
}
