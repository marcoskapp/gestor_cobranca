package br.com.gestorcobranca.telephony.domain.model;

/**
 * Escopo de uma regra de roteamento, do mais específico ao mais amplo. A conta de uma discagem é a
 * da regra mais específica que tenha conta utilizável: campanha → carteira → credor → padrão.
 */
public enum RouteScope {
    CAMPAIGN,
    PORTFOLIO,
    CREDITOR,
    DEFAULT
}
