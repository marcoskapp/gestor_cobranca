package br.com.gestorcobranca.payment.domain.model;

/** Fonte de um pagamento externo: conta da assessoria, pago diretamente ao credor, informado pelo credor ou outra. */
public enum ExternalOrigin {
    AGENCY_ACCOUNT, CREDITOR_DIRECT, CREDITOR_REPORT, OTHER
}
