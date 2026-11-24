package br.com.gestorcobranca.settlement.domain.model;

/** Quem registrou a resposta do credor ao demonstrativo enviado. */
public enum ResponseOrigin {
    /** A assessoria, com a resposta recebida por outro canal (e-mail, carta…). */
    AGENCY,
    /** A própria pessoa do credor, no portal do credor (com termo gravado). */
    CREDITOR_PORTAL
}
