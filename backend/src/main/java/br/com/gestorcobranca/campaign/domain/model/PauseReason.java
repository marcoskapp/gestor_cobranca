package br.com.gestorcobranca.campaign.domain.model;

/**
 * Motivo de pausa de uma inscrição. As automáticas vêm dos eventos de integração (pagamento,
 * acordo, contestação, restrição de contato, caso suspenso/jurídico); {@code MANUAL} é da operação.
 */
public enum PauseReason {
    PAYMENT_CONFIRMED,
    AGREEMENT_FORMALIZED,
    DISPUTE_OPENED,
    CONTACT_RESTRICTED,
    CASE_SUSPENDED,
    CASE_LEGAL,
    MANUAL;

    /**
     * Retomada automática quando a causa deixa de existir na posição atual do caso (contestação
     * encerrada, restrição revogada, caso fora da suspensão/jurídico, acordo quebrado ou cancelado).
     * Pagamento confirmado retoma após o prazo da campanha ({@code paymentPauseDays}); manual, só
     * por comando.
     */
    public boolean resumesWhenCleared() {
        return this != PAYMENT_CONFIRMED && this != MANUAL;
    }
}
