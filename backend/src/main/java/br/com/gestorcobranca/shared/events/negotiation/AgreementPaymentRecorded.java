package br.com.gestorcobranca.shared.events.negotiation;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Resultado da baixa de um {@code PaymentConfirmed} num acordo, publicado pelo negotiation para o
 * módulo payment (que não pode ser chamado pelo negotiation, pelo grafo de dependências). Com
 * {@code unappliedAmount} positivo (acordo encerrado, excedente ou acordo desconhecido), o valor fica
 * disponível para realocação no payment ("pagamentos não aplicados").
 *
 * @param paymentId       o {@code paymentId} do evento de origem (id da alocação no payment)
 * @param status          APPLIED (integral ou com excedente), UNAPPLIED (acordo encerrado) ou IGNORED
 *                        (acordo inexistente no tenant)
 * @param agreementStatus situação do acordo após a baixa (nulo quando IGNORED)
 * @param receivedBy      quem recebeu o dinheiro, repetido do {@code PaymentConfirmed} de origem: AGENCY ou CREDITOR
 *                        (H07 parte 2b — fonte única para a prestação de contas); nulo = não informado (evento no
 *                        formato anterior ao campo, ou {@code PaymentConfirmed} de origem sem o campo): o consumidor
 *                        mantém a dedução por outra fonte (ADR-0004, evolução de eventos; decisão 1630)
 */
public record AgreementPaymentRecorded(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID agreementId,
        UUID paymentId,
        UUID creditorId,
        UUID debtorId,
        BigDecimal amount,
        BigDecimal appliedAmount,
        BigDecimal unappliedAmount,
        String status,
        String agreementStatus,
        String receivedBy) implements IntegrationEvent {

    public static final String RECEIVED_BY_AGENCY = "AGENCY";
    public static final String RECEIVED_BY_CREDITOR = "CREDITOR";

    public AgreementPaymentRecorded {
        if (receivedBy != null && !RECEIVED_BY_AGENCY.equals(receivedBy) && !RECEIVED_BY_CREDITOR.equals(receivedBy)) {
            throw new IllegalArgumentException("receivedBy inválido: " + receivedBy);
        }
    }

    /** Assinatura anterior ao H07 parte 2b: quem recebeu não é informado ({@code receivedBy} nulo). */
    public AgreementPaymentRecorded(UUID eventId, UUID tenantId, Instant occurredAt, UUID agreementId, UUID paymentId,
                                    UUID creditorId, UUID debtorId, BigDecimal amount, BigDecimal appliedAmount,
                                    BigDecimal unappliedAmount, String status, String agreementStatus) {
        this(eventId, tenantId, occurredAt, agreementId, paymentId, creditorId, debtorId, amount, appliedAmount,
                unappliedAmount, status, agreementStatus, null);
    }

    /** Dinheiro recebido diretamente pelo credor (falso quando não informado). */
    public boolean receivedByCreditor() {
        return RECEIVED_BY_CREDITOR.equals(receivedBy);
    }

    /** O evento diz quem recebeu (formato do H07 parte 2b em diante). */
    public boolean receivedByInformed() {
        return receivedBy != null;
    }
}
