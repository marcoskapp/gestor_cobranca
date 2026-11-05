package br.com.gestorcobranca.collection.domain.model;

/**
 * Por que o e-mail do credor ficou em quarentena. {@link #VIRUS} e {@link #AUTHENTICATION_FAILED} nunca são liberados
 * (só descartados); os demais podem ser liberados pela gestão depois de conferir o original.
 */
public enum MailQuarantineReason {
    /** Remetente não é contato de contestação ativo do credor do pedido. */
    SENDER_NOT_REGISTERED(true),
    /** Sem DMARC PASS (ou, sem política DMARC no domínio, sem SPF e DKIM PASS): possível falsificação. */
    AUTHENTICATION_FAILED(false),
    /** Varredura de vírus do SES sem PASS. */
    VIRUS(false),
    SPAM(true),
    /** Remetente acima do limite de e-mails por hora. */
    RATE_LIMITED(true),
    TOO_LARGE(true),
    TOO_MANY_ATTACHMENTS(true),
    /** Endereço de resposta vencido (pedido antigo). */
    ROUTE_EXPIRED(true),
    /** A contestação já estava encerrada. */
    DISPUTE_CLOSED(false),
    /** Mensagem que não pôde ser lida (MIME inválido). */
    MALFORMED(false);

    private final boolean releasable;

    MailQuarantineReason(boolean releasable) {
        this.releasable = releasable;
    }

    /** A gestão pode liberar o e-mail para o analista (depois de conferir o original). */
    public boolean releasable() {
        return releasable;
    }
}
