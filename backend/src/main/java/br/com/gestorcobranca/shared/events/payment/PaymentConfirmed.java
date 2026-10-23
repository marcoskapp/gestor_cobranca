package br.com.gestorcobranca.shared.events.payment;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pagamento confirmado e alocado (RB06). Somente pagamentos confirmados contam como
 * recuperação financeira efetiva (RB08). Dispara: pausa de réguas, recálculo de elegibilidade,
 * apuração de comissão e atualização de score.
 *
 * @param source       PROVIDER (integrado) ou EXTERNAL (informado e revisado)
 * @param method       PIX, BOLETO, CARD, TRANSFER, CASH, OTHER
 * @param allocations  alocação por título/parcela
 */
public record PaymentConfirmed(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID paymentId,
        UUID debtorId,
        UUID creditorId,
        UUID portfolioId,
        UUID agreementId,
        BigDecimal amount,
        Instant paidAt,
        String source,
        String method,
        List<Allocation> allocations) implements IntegrationEvent {

    public PaymentConfirmed {
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
    }

    /**
     * Pagamento de parcela de acordo: o módulo {@code negotiation} é quem distribui o valor entre
     * os títulos e componentes do acordo e o lança no razão do título ({@code DebtLedgerPort});
     * o módulo {@code payment} não lança no razão as alocações com {@code agreementInstallmentId}
     * (ver {@code docs/design/negociacao-e-alcadas.md} §10).
     *
     * @param titleId                título afetado; obrigatório em pagamento sem acordo. Em alocação a
     *                               parcela de acordo é opcional (informativo — a distribuição por
     *                               título é do negotiation)
     * @param agreementInstallmentId parcela de acordo, quando aplicável
     * @param amount                 valor alocado
     */
    public record Allocation(UUID titleId, UUID agreementInstallmentId, BigDecimal amount) {
    }
}
