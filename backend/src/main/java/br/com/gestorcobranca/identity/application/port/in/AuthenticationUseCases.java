package br.com.gestorcobranca.identity.application.port.in;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Fluxo de autenticação:
 * <pre>
 * login ──► AUTHENTICATED (tokens)
 *       ├─► MFA_REQUIRED ──────────► verifyMfa ──► AUTHENTICATED
 *       ├─► MFA_ENROLLMENT_REQUIRED ► startMfaEnrollment / confirmMfaEnrollment ──► AUTHENTICATED
 *       └─► PASSWORD_CHANGE_REQUIRED ► changeExpiredPassword ──► (novo login)
 * </pre>
 * Etapas intermediárias usam um {@code stepToken} de curta duração e finalidade única.
 */
public interface AuthenticationUseCases {

    LoginResult login(LoginCommand command);

    LoginResult verifyMfa(String stepToken, String code, ClientInfo client);

    MfaEnrollment startMfaEnrollment(String stepToken);

    LoginResult confirmMfaEnrollment(String stepToken, String code, ClientInfo client);

    /** Troca de senha obrigatória (expirada ou primeiro acesso) via step token. */
    void changeExpiredPassword(String stepToken, String newPassword);

    LoginResult refresh(String refreshToken, ClientInfo client);

    /** Encerra a sessão do usuário autenticado. */
    void logout();

    /**
     * Solicita redefinição de senha. Sempre conclui sem erro (não revela se o e-mail existe).
     */
    void requestPasswordReset(String tenantSlug, String email);

    /** Conclui ativação de conta ou redefinição de senha a partir do token recebido por e-mail. */
    void completeCredentialAction(String actionToken, String newPassword);

    record ClientInfo(String ip, String userAgent) {
    }

    record LoginCommand(String tenantSlug, String email, String password, ClientInfo client) {
    }

    enum LoginStatus { AUTHENTICATED, MFA_REQUIRED, MFA_ENROLLMENT_REQUIRED, PASSWORD_CHANGE_REQUIRED }

    /**
     * @param refreshToken entregue ao cliente em cookie HttpOnly — nunca no corpo da resposta
     */
    record LoginResult(LoginStatus status, String accessToken, Instant accessTokenExpiresAt, String refreshToken,
                       Instant refreshTokenExpiresAt, String stepToken, AuthenticatedUserView user) {

        public static LoginResult step(LoginStatus status, String stepToken) {
            return new LoginResult(status, null, null, null, null, stepToken, null);
        }
    }

    record MfaEnrollment(String secret, String otpauthUri) {
    }

    record AuthenticatedUserView(UUID id, UUID tenantId, String tenantSlug, String tenantName, String name, String email,
                                 Set<String> roles, Set<String> permissions, boolean allCreditors, Set<UUID> creditorIds,
                                 boolean mfaEnabled) {
    }
}
