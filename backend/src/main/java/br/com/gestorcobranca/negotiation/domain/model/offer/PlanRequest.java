package br.com.gestorcobranca.negotiation.domain.model.offer;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;

import java.time.LocalDate;

/**
 * Forma de pagamento pedida.
 *
 * @param installments número total de pagamentos, incluindo a entrada (1 = à vista)
 * @param downPayment  entrada desejada (opcional; nula = mínima exigida ou parcelas iguais)
 * @param firstDueDate vencimento do 1º pagamento (à vista ou entrada)
 */
public record PlanRequest(int installments, Money downPayment, LocalDate firstDueDate) {

    public PlanRequest {
        Guard.require(installments >= 1 && installments <= 360, "negotiation.installments_invalid",
                "Número de parcelas entre 1 e 360");
        Guard.require(firstDueDate != null, "negotiation.first_due_date_required", "Vencimento do 1º pagamento obrigatório");
        Guard.require(downPayment == null || downPayment.isPositive(), "negotiation.down_payment_invalid",
                "Entrada deve ser positiva");
    }
}
