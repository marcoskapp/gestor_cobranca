package br.com.gestorcobranca.debt.domain.model;

/**
 * Quem recebeu o dinheiro de um pagamento lançado no razão (H07, decisão 1466): a assessoria (conta da assessoria ou
 * meio integrado) ou o próprio credor (pago diretamente ao credor e informado por ele, pela integração ou por quem
 * registrou). Só os lançamentos de pagamento e de estorno têm receptor; o estorno repete o do pagamento estornado.
 * O título mostra o selo "Pago ao credor" enquanto houver valor líquido recebido pelo credor.
 */
public enum PaymentReceiver {
    AGENCY, CREDITOR
}
