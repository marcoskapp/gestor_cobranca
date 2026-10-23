package br.com.gestorcobranca.negotiation.domain.model.calculation;

import br.com.gestorcobranca.shared.domain.Money;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Situação de um título na data do cálculo, lida do razão do módulo debt: saldo de principal
 * (já líquido de pagamentos e descontos) e encargos já reconhecidos em aberto (ex.: de um acordo
 * anterior). O cálculo de mora incide somente sobre o principal; os encargos reconhecidos entram
 * na composição como estão.
 *
 * @param paidTotal pagamentos brutos já recebidos (informativo na composição)
 */
public record TitleSnapshot(UUID titleId, UUID portfolioId, LocalDate dueDate, Money principalBalance,
                            Money recognizedInterest, Money recognizedFine, Money recognizedFees,
                            Money recognizedHonorarium, Money paidTotal) {

    public TitleSnapshot {
        Objects.requireNonNull(titleId, "titleId");
        Objects.requireNonNull(dueDate, "dueDate");
        principalBalance = orZero(principalBalance);
        recognizedInterest = orZero(recognizedInterest);
        recognizedFine = orZero(recognizedFine);
        recognizedFees = orZero(recognizedFees);
        recognizedHonorarium = orZero(recognizedHonorarium);
        paidTotal = orZero(paidTotal);
    }

    public static TitleSnapshot principalOnly(UUID titleId, LocalDate dueDate, Money principal) {
        return new TitleSnapshot(titleId, null, dueDate, principal, null, null, null, null, null);
    }

    private static Money orZero(Money m) {
        return m == null ? Money.ZERO : m;
    }

    public Breakdown recognized() {
        return new Breakdown(Money.ZERO, Money.ZERO, recognizedInterest, recognizedFine, recognizedFees,
                recognizedHonorarium);
    }
}
