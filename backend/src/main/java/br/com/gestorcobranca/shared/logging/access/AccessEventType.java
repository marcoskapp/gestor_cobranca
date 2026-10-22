package br.com.gestorcobranca.shared.logging.access;

/** Tipos de evento de acesso e segurança registrados para rastreabilidade. */
public enum AccessEventType {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGIN_BLOCKED,
    MFA_CHALLENGE,
    MFA_SUCCESS,
    MFA_FAILURE,
    MFA_ENROLLED,
    MFA_RESET,
    TOKEN_REFRESHED,
    TOKEN_REUSE_DETECTED,
    LOGOUT,
    SESSION_REVOKED,
    SESSION_EXPIRED,
    PASSWORD_CHANGED,
    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET_COMPLETED,
    USER_CREATED,
    USER_UPDATED,
    USER_DISABLED,
    USER_ENABLED,
    USER_UNLOCKED,
    ROLE_CHANGED,
    PERMISSION_CHANGED,
    SCOPE_CHANGED,
    /** Requisição autenticada negada por permissão. */
    ACCESS_DENIED,
    /** Tentativa de acessar recurso de credor fora do escopo. */
    SCOPE_DENIED,
    /** Requisição sem autenticação válida em endpoint protegido. */
    UNAUTHENTICATED,
    /** Visualização de dado pessoal sem máscara (documento, contato). */
    SENSITIVE_DATA_VIEWED,
    /** Exportação de dados (relatórios, evidências). */
    DATA_EXPORTED,
    /** Download de arquivo armazenado (termos, evidências, importações). */
    FILE_DOWNLOADED,
    /** Webhook recebido com assinatura inválida. */
    WEBHOOK_REJECTED,
    /** Acesso do devedor ao portal de autonegociação. */
    PORTAL_LOGIN_SUCCESS,
    PORTAL_LOGIN_FAILURE,
    RATE_LIMITED
}
