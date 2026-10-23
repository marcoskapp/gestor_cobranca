package br.com.gestorcobranca.negotiation.domain.model.offer;

import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cronograma de pagamento de um valor negociado.
 *
 * @param negotiated       valor negociado (líquido de descontos)
 * @param downPayment      entrada (igual ao valor negociado quando à vista)
 * @param periodRate       juros do parcelamento por período (%; 0 sem juros), 10 casas
 * @param schedule         pagamentos em ordem
 * @param total            soma dos pagamentos (valor do acordo)
 * @param financingInterest juros do parcelamento ({@code total − negotiated})
 */
public record PaymentPlan(int installments, Money negotiated, Money downPayment, BigDecimal periodRate,
                          List<ScheduledPayment> schedule, Money total, Money financingInterest) {

    public PaymentPlan {
        schedule = List.copyOf(schedule);
    }

    /** Menor pagamento após a entrada (a própria entrada quando à vista). */
    public Money smallestInstallment() {
        return schedule.stream().skip(installments > 1 ? 1 : 0).map(ScheduledPayment::amount).min(Money::compareTo)
                .orElse(Money.ZERO);
    }
}
