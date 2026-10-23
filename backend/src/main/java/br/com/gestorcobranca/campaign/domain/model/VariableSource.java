package br.com.gestorcobranca.campaign.domain.model;

/**
 * Origem do valor de uma variável de template numa etapa de mensagem. Os valores são calculados no
 * momento do envio e entregues ao communication (que valida o tipo e guarda criptografado); nada é
 * persistido no módulo campaign. {@code LITERAL} usa o texto informado após {@code LITERAL:}.
 */
public enum VariableSource {
    DEBTOR_FIRST_NAME,
    OPEN_BALANCE,
    ELIGIBLE_BALANCE,
    OLDEST_DUE_DATE,
    DAYS_OVERDUE,
    CREDITOR_NAME,
    OFFER_AMOUNT,
    OFFER_DUE_DATE,
    PAYMENT_LINK,
    LITERAL;

    public boolean requiresOffer() {
        return this == OFFER_AMOUNT || this == OFFER_DUE_DATE || this == PAYMENT_LINK;
    }
}
