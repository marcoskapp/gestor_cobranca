package br.com.gestorcobranca.compliance.domain.model;

/** Ação de descarte ao fim do prazo de retenção. */
public enum RetentionAction {
    /** Eliminar dados pessoais preservando registros financeiros. */
    ANONYMIZE,
    /** Remover fisicamente o arquivo armazenado. */
    DELETE_FILE
}
