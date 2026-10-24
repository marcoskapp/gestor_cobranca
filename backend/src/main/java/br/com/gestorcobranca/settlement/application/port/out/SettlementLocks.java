package br.com.gestorcobranca.settlement.application.port.out;

import java.util.UUID;

/** Serialização dos eventos de uma mesma alocação (recebimento × estorno chegam em qualquer ordem). */
public interface SettlementLocks {

    /** Lock consultivo transacional pela alocação (liberado no fim da transação). */
    void lockAllocation(UUID allocationId);

    /** Lock consultivo transacional pelo acordo (valor fixo devido uma única vez por acordo). */
    void lockAgreement(UUID agreementId);

    /** Lock consultivo transacional pela despesa do jurídico (lançamento × cancelamento). */
    void lockExpense(UUID legalExpenseId);

    /** Lock consultivo transacional pelo credor (cálculo do demonstrativo × apuração concorrente). */
    void lockCreditor(UUID creditorId);
}
