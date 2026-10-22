package br.com.gestorcobranca.identity.application;

import br.com.gestorcobranca.identity.domain.model.LockoutPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Parâmetros de segurança de identidade ({@code app.security.*}). */
@ConfigurationProperties(prefix = "app.security")
public record IdentityProperties(Session session, Password password, Lockout lockout, Cors cors, RateLimit rateLimit,
                                 Mfa mfa) {

    /**
     * @param refreshTokenTtl  duração máxima da sessão (expiração absoluta)
     * @param idleTimeout      inatividade máxima
     * @param maxSessionsPerUser sessões simultâneas; a mais antiga é revogada ao exceder
     */
    public record Session(Duration refreshTokenTtl, Duration idleTimeout, int maxSessionsPerUser) {
    }

    public record Password(int minLength, int history, Duration maxAge) {
    }

    public record Lockout(int maxFailedAttempts, Duration baseLockDuration, Duration maxLockDuration) {

        public LockoutPolicy toPolicy() {
            return new LockoutPolicy(maxFailedAttempts, baseLockDuration, maxLockDuration);
        }
    }

    public record Cors(List<String> allowedOrigins) {
    }

    public record RateLimit(int loginPerMinutePerIp) {
    }

    /** @param requiredForRoles códigos de perfil para os quais MFA é obrigatório */
    public record Mfa(String issuer, Set<String> requiredForRoles) {
    }
}
