package br.com.gestorcobranca.communication.domain.model;

/** Aprovação do template no provedor (obrigatória para o WhatsApp iniciar contato). */
public enum ApprovalStatus {
    /** Canal sem aprovação prévia (e-mail, SMS). */
    NOT_REQUIRED,
    /** Submetido ao provedor, aguardando decisão. */
    PENDING,
    APPROVED,
    REJECTED
}
