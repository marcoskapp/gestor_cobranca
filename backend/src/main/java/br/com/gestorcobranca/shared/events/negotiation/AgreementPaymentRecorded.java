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
        String agreementStatus) implements IntegrationEvent {
}
