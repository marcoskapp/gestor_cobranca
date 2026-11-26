package br.com.gestorcobranca.identity.application.port.in.creditor;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Autenticação da pessoa do credor (ADR 0011). Ordem do login: senha → MFA (sempre obrigatório) → troca da senha
 * vencida, se houver → sessão. Falhas respondem sempre a mesma mensagem genérica (o motivo real fica no log de acesso);
 * o contador de bloqueio é persistido antes da resposta.
 */
public interface CreditorAuthentication {

    /** Marca da assessoria para a tela de entrada; vazio para assessoria inexistente ou inativa. */
    Optional<SiteView> site(String tenantSlug);

    CreditorLoginResult login(String tenantSlug, String email, String password, CreditorClient client);

    CreditorLoginResult verifyMfa(String stepToken, String code, CreditorClient client);

    /** Senha vencida (depois do MFA): define a nova senha e abre a sessão. */
    CreditorLoginResult changeExpiredPassword(String stepToken, String newPassword, CreditorClient client);

    /**
     * Abre a ativação do convite: gera o segredo do autenticador (ainda não confirmado). O convite só é consumido em
     * {@link #completeActivation}. Link inválido, vencido, usado ou de conta revogada: a mesma recusa.
     */
    ActivationStart startActivation(String invitationToken);

    /** Conclui a ativação numa transação: senha + código do autenticador; só então abre a sessão. */
    CreditorLoginResult completeActivation(String invitationToken, String newPassword, String code, CreditorClient client);

    CreditorLoginResult refresh(String refreshToken, CreditorClient client);

    /** Encerra a sessão corrente da pessoa do credor. */
    void logout();

    /** Dados da conta autenticada (pessoa, perfil, credor e assessoria). */
    CreditorMeView me();

    /** Sempre a mesma resposta; link de redefinição só para conta ativa. */
    void requestPasswordReset(String tenantSlug, String email, CreditorClient client);

    /** Nova senha pelo link; não zera o bloqueio por tentativas (só o login completo com MFA zera). */
    void completePasswordReset(String resetToken, String newPassword, CreditorClient client);

    record CreditorClient(String ip, String userAgent) {
    }

    enum LoginStatus { AUTHENTICATED, MFA_REQUIRED, PASSWORD_CHANGE_REQUIRED }

    /**
     * @param refreshToken só para o cookie {@code HttpOnly}; nunca no corpo da resposta
     */
    record CreditorLoginResult(LoginStatus status, String accessToken, Instant accessTokenExpiresAt, String refreshToken,
                               Instant refreshTokenExpiresAt, String stepToken, CreditorMeView me) {

        public static CreditorLoginResult step(LoginStatus status, String stepToken) {
            return new CreditorLoginResult(status, null, null, null, null, stepToken, null);
        }

        /** Sem os tokens (acesso, refresh e etapa) nem a conta: nada de credencial em log ou mensagem de erro (RS-82). */
        @Override
        public String toString() {
            return "CreditorLoginResult[status=%s, accessTokenExpiresAt=%s, refreshTokenExpiresAt=%s]"
                    .formatted(status, accessTokenExpiresAt, refreshTokenExpiresAt);
        }
    }

    record CreditorMeView(UUID userId, String name, String email, String role, String roleLabel, Set<String> permissions,
                          UUID creditorId, String creditorName, String tenantSlug, String tenantName, Instant lastLoginAt) {
    }

    /** @param account e-mail mascarado da conta convidada (para a pessoa conferir que o link é dela) */
    record ActivationStart(String name, String account, String creditorName, String tenantName, String secret,
                           String otpAuthUri) {

        /** Sem o segredo do autenticador nem o {@code otpauth://} (RS-82): o segredo só vai no corpo da resposta. */
        @Override
        public String toString() {
            return "ActivationStart[creditorName=%s, tenantName=%s, secret=***, otpAuthUri=***]"
                    .formatted(creditorName, tenantName);
        }
    }

    record SiteView(String slug, String name) {
    }

    /** Muitas tentativas a partir do mesmo IP: bloqueado até {@code until} (o controller responde 429). */
    final class LoginThrottledException extends RuntimeException {

        private final transient Instant until;

        public LoginThrottledException(Instant until) {
            super("Muitas tentativas. Aguarde alguns minutos e tente de novo.");
            this.until = until;
        }

        public Instant until() {
            return until;
        }
    }
}
