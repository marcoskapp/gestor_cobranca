package br.com.gestorcobranca.shared.security;

/**
 * Falha de autenticação (HTTP 401). A mensagem é sempre genérica para não revelar se o usuário
 * existe, se a senha estava errada ou se a conta está bloqueada; o motivo real vai apenas para o
 * log de acesso.
 */
public class AuthenticationFailedException extends RuntimeException {

    private final String code;

    public AuthenticationFailedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static AuthenticationFailedException invalidCredentials() {
        return new AuthenticationFailedException("auth.invalid_credentials", "Credenciais inválidas");
    }

    public static AuthenticationFailedException invalidToken() {
        return new AuthenticationFailedException("auth.invalid_token", "Sessão inválida ou expirada. Faça login novamente.");
    }

    public String code() {
        return code;
    }
}
