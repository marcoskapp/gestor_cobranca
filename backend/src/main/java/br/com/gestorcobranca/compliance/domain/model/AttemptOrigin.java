package br.com.gestorcobranca.compliance.domain.model;

/** Origem de uma tentativa de contato. */
public enum AttemptOrigin {
    /** Contato manual do operador (ligação, mensagem avulsa). */
    OPERATOR,
    /** Passo de campanha ou régua. */
    CAMPAIGN,
    /** Envio automático do sistema (lembrete, notificação). */
    SYSTEM
}
