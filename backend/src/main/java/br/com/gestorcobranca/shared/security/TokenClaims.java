package br.com.gestorcobranca.shared.security;

/** Nomes dos claims JWT emitidos pela plataforma. */
public final class TokenClaims {

    /**
     * Tipo do token: {@code access}, {@code mfa}, {@code mfa_enroll}, {@code pwd_change}, {@code portal} e, no portal do
     * credor, {@code creditor} e as etapas {@code creditor_mfa}, {@code creditor_mfa_enroll}, {@code creditor_pwd}.
     */
    public static final String TYPE = "typ";
    public static final String TENANT = "tid";
    public static final String SESSION = "sid";
    public static final String NAME = "name";
    public static final String PERMISSIONS = "perm";
    public static final String ALL_CREDITORS = "cs_all";
    public static final String CREDITORS = "cs";
    /** Credor do portal em que o devedor se autenticou (token {@code portal}) ou da pessoa do credor ({@code creditor}). */
    public static final String CREDITOR = "cid";

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_MFA = "mfa";
    public static final String TYPE_MFA_ENROLL = "mfa_enroll";
    public static final String TYPE_PASSWORD_CHANGE = "pwd_change";
    public static final String TYPE_PORTAL = "portal";
    /** Pessoa do credor autenticada no portal do credor. */
    public static final String TYPE_CREDITOR = "creditor";
    /** Etapas do login do credor: distintas das do usuário interno, para um token de etapa nunca servir ao outro. */
    public static final String TYPE_CREDITOR_MFA = "creditor_mfa";
    public static final String TYPE_CREDITOR_MFA_ENROLL = "creditor_mfa_enroll";
    public static final String TYPE_CREDITOR_PASSWORD = "creditor_pwd";

    /** Audiência dos tokens de usuários internos (API {@code /api/v1}). */
    public static final String AUDIENCE = "gestor-cobranca-api";

    /**
     * Audiência dos tokens do devedor no portal de autonegociação (API {@code /api/portal/v1}). Um
     * token de uma audiência nunca é aceito como da outra: o decodificador exige que tipo e
     * audiência correspondam ({@code portal} ⇔ {@link #PORTAL_AUDIENCE}).
     */
    public static final String PORTAL_AUDIENCE = "gestor-cobranca-portal";

    /**
     * Audiência dos tokens do portal do credor (API {@code /api/creditor/v1}), inclusive os de etapa do login: tipo
     * {@code creditor*} ⇔ esta audiência, exclusivamente.
     */
    public static final String CREDITOR_AUDIENCE = "gestor-cobranca-creditor";

    private TokenClaims() {
    }
}
