package br.com.gestorcobranca.complaint.domain.model;

/** Assunto principal da reclamação (classificação para os indicadores e a monitoria). */
public enum ComplaintSubject {
    /** Cobrança abusiva, constrangimento ou ameaça (CDC art. 42 e 71). */
    ABUSIVE_COLLECTION,
    /** Excesso de contatos, horário ou canal impróprio. */
    CONTACT_FREQUENCY,
    /** Contato com terceiros (familiar, trabalho, vizinho). */
    THIRD_PARTY_CONTACT,
    /** Não reconhece a dívida ou já pagou. */
    DEBT_NOT_RECOGNIZED,
    /** Valor, juros ou encargos contestados. */
    WRONG_AMOUNT,
    /** Acordo, boleto ou baixa de pagamento. */
    AGREEMENT_OR_PAYMENT,
    /** Negativação ou protesto. */
    CREDIT_BUREAU,
    /** Privacidade e dados pessoais (LGPD). */
    DATA_PRIVACY,
    OTHER
}
