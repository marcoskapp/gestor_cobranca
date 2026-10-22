package br.com.gestorcobranca.identity.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Cookie do refresh token: {@code HttpOnly} (inacessível a JavaScript, mitigando XSS),
 * {@code Secure}, {@code SameSite=Strict} (não enviado em requisições cross-site, mitigando CSRF)
 * e restrito ao caminho dos endpoints de autenticação.
 */
@Component
class RefreshCookie {

    static final String NAME = "gc_refresh";
    private static final String PATH = "/api/v1/auth";

    private final boolean secure;
    private final Clock clock;

    RefreshCookie(@Value("${app.security.cookie-secure:true}") boolean secure, Clock clock) {
        this.secure = secure;
        this.clock = clock;
    }

    String create(String token, Instant expiresAt) {
        Duration maxAge = Duration.between(Instant.now(clock), expiresAt);
        return ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build()
                .toString();
    }

    String clear() {
        return ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(Duration.ZERO)
                .build()
                .toString();
    }
}
