package br.com.gestorcobranca.collection.domain.model;

/**
 * Promessa de pagamento: {@code PENDING → KEPT | BROKEN | CANCELLED}. Cumprida quando os
 * pagamentos confirmados nos títulos do caso desde o registro alcançam o valor prometido;
 * quebrada quando a data prometida mais a tolerância passa sem isso.
 */
public enum PromiseStatus {
    PENDING,
    KEPT,
    BROKEN,
    CANCELLED
}
