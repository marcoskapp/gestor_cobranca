package br.com.gestorcobranca.settlement.domain.model.rules;

/**
 * Quem recebeu o dinheiro: a conta da assessoria (repassa ao credor) ou o próprio credor (pagamento
 * direto ao credor informado pela origem {@code CREDITOR_DIRECT}/{@code CREDITOR_REPORT}): nesse caso o
 * credor deve à assessoria a comissão e os honorários dela.
 */
public enum ReceivedBy {
    AGENCY,
    CREDITOR
}
