package br.com.gestorcobranca.shared.privacy;

/**
 * Finalidade do compartilhamento (código estável; o texto ao titular é montado pelo compliance). Contrato com a
 * interface e com a resposta ao titular: não renomear.
 */
public enum DataSharingPurpose {
    /** Cobrança da dívida do credor (titularidade dos títulos). */
    DEBT_COLLECTION,
    /** Envio e recebimento de mensagens (WhatsApp, e-mail, SMS). */
    MESSAGING,
    /** Ligações telefônicas (e gravação, quando habilitada). */
    PHONE_CALLS,
    /** Emissão de cobranças Pix e confirmação de pagamento. */
    PAYMENT_COLLECTION,
    /** Atendimento pelo assistente virtual (modelo de linguagem). */
    VIRTUAL_ASSISTANT,
    /** Cobrança judicial ou extrajudicial por escritório parceiro. */
    LEGAL_COLLECTION,
    /** Envio de dados pelo sistema do credor por integração. */
    CREDITOR_INTEGRATION
}
