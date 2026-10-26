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
    /** Consulta a remuneração (extrato, razão ou simulação de comissão) de outro usuário. */
    COMPENSATION_DATA_VIEWED,
    /** Exportação de dados (relatórios, evidências). */
    DATA_EXPORTED,
    /** Download de arquivo armazenado (termos, evidências, importações). */
    FILE_DOWNLOADED,
    /** Webhook recebido com assinatura inválida. */
    WEBHOOK_REJECTED,
    /** Acesso do devedor ao portal de autonegociação (sessão aberta por link ou documento + código). */
    PORTAL_LOGIN_SUCCESS,
    /** Código, link ou desafio de conhecimento inválido no portal (sem revelar se o documento existe). */
    PORTAL_LOGIN_FAILURE,
    /** Pedido de código de acesso ao portal (registrado mesmo para documento inexistente). */
    PORTAL_CODE_REQUESTED,
    /** Link de acesso de uso único emitido (por operador ou sistema). */
    PORTAL_LINK_ISSUED,
    /** Bloqueio progressivo do acesso ao portal por documento ou IP. */
    PORTAL_LOCKED,
    /** Sessão do portal encerrada pelo devedor. */
    PORTAL_LOGOUT,
    /** Sessão do portal revogada (gestão, anonimização, novo acesso acima do limite). */
    PORTAL_SESSION_REVOKED,
    /** Aviso de privacidade do portal reconhecido pelo devedor. */
    PORTAL_PRIVACY_ACKNOWLEDGED,
    RATE_LIMITED,
    /** Endereço de integração financeira (URL do PSP, autenticação do webhook) criado ou alterado — alerta. */
    INTEGRATION_ENDPOINT_CHANGED,
    /**
     * Consulta de dados feita pelo chat de suporte em nome do usuário (ferramenta somente leitura), registrada como
     * se ele tivesse aberto a tela: ferramenta, tipo de registro, ids consultados e conversa — nunca o conteúdo.
     */
    SUPPORT_DATA_QUERIED,
    /** Chamado de suporte aberto num rastreador externo (issue no GitHub) com o e-mail de quem abriu. */
    SUPPORT_TICKET_OPENED
}
