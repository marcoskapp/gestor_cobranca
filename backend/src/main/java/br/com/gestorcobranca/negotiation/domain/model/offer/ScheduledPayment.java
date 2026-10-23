package br.com.gestorcobranca.negotiation.domain.model.offer;

import br.com.gestorcobranca.shared.domain.Money;

import java.time.LocalDate;

/**
 * Pagamento do cronograma. O número 1 é o pagamento à vista ou a entrada.
 *
 * @param interest      juros do parcelamento embutidos no pagamento
 * @param amortization  parte que amortiza o valor negociado
 * @param balanceAfter  saldo financiado após o pagamento
 */
public record ScheduledPayment(int number, LocalDate dueDate, Money amount, Money interest, Money amortization,
                               Money balanceAfter) {
}
