package br.com.gestorcobranca.collection.domain.model;

/** Natureza da contestação (F21). */
public enum DisputeType {
    /** Devedor não reconhece a dívida. */
    NOT_RECOGNIZED,
    /** Alega já ter pago. */
    ALREADY_PAID,
    /** Contesta o valor (encargos, cálculo). */
    WRONG_AMOUNT,
    /** Suspeita de fraude (contratação por terceiro). */
    FRAUD,
    /** Alega prescrição. */
    PRESCRIBED,
    /** Produto ou serviço não entregue / com defeito. */
    PRODUCT_OR_SERVICE,
    OTHER
}
