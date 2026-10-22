package br.com.gestorcobranca.shared.security;

/** Nomes dos claims JWT emitidos pela plataforma. */
public final class TokenClaims {

    /** Tipo do token: {@code access}, {@code mfa}, {@code mfa_enroll}, {@code portal}. */
    public static final String TYPE = "typ";
    public static final String TENANT = "tid";
    public static final String SESSION = "sid";
    public static final String NAME = "name";
    public static final String PERMISSIONS = "perm";
    public static final String ALL_CREDITORS = "cs_all";
    public static final String CREDITORS = "cs";

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_MFA = "mfa";
    public static final String TYPE_MFA_ENROLL = "mfa_enroll";
    public static final String TYPE_PASSWORD_CHANGE = "pwd_change";
    public static final String TYPE_PORTAL = "portal";

    public static final String AUDIENCE = "gestor-cobranca-api";

    private TokenClaims() {
    }
}
