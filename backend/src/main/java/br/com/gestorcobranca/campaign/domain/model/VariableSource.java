package br.com.gestorcobranca.campaign.domain.model;

/**
 * Origem do valor de uma variável de template numa etapa de mensagem. Os valores são calculados no
 * momento do envio e entregues ao communication (que valida o tipo e guarda criptografado); nada é
 * persistido no módulo campaign. {@code LITERAL} usa o texto informado após {@code LITERAL:}.
 * <ul>
 *   <li>{@code NEXT_INSTALLMENT_*}: próxima parcela em aberto do acordo em vigor do caso (negotiation);
 *       sem acordo em vigor ou sem parcela em aberto, a etapa é pulada ({@code NO_ACTIVE_AGREEMENT},
 *       {@code NO_OPEN_INSTALLMENT}).</li>
 *   <li>{@code PORTAL_LINK}: link de uso único do portal gerado no envio (portal); {@code PORTAL_LINK_HOURS}:
 *       validade desse link, só junto com ele. Sem portal para o caso, a etapa é pulada
 *       ({@code PORTAL_UNAVAILABLE}).</li>
 * </ul>
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
    NEXT_INSTALLMENT_AMOUNT,
    NEXT_INSTALLMENT_DUE_DATE,
    PORTAL_LINK,
    PORTAL_LINK_HOURS,
    LITERAL;

    public boolean requiresOffer() {
        return this == OFFER_AMOUNT || this == OFFER_DUE_DATE || this == PAYMENT_LINK;
    }

    public boolean requiresInstallment() {
        return this == NEXT_INSTALLMENT_AMOUNT || this == NEXT_INSTALLMENT_DUE_DATE;
    }

    public boolean requiresPortalLink() {
        return this == PORTAL_LINK || this == PORTAL_LINK_HOURS;
    }
}
