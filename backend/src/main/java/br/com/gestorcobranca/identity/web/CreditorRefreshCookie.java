package br.com.gestorcobranca.identity.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Cookie do refresh token do portal do credor: nome e caminho próprios ({@code gc_creditor_refresh},
 * {@code /api/creditor/v1/auth}), {@code HttpOnly}, {@code Secure} e {@code SameSite=Strict} — nunca se mistura com o
 * cookie do usuário interno.
 */
@Component
class CreditorRefreshCookie {

    static final String NAME = "gc_creditor_refresh";
    private static final String PATH = "/api/creditor/v1/auth";

    private final boolean secure;
    private final Clock clock;

    CreditorRefreshCookie(@Value("${app.security.cookie-secure:true}") boolean secure, Clock clock) {
        this.secure = secure;
        this.clock = clock;
    }

    String create(String token, Instant expiresAt) {
        Duration maxAge = Duration.between(Instant.now(clock), expiresAt);
        return ResponseCookie.from(NAME, token).httpOnly(true).secure(secure).sameSite("Strict").path(PATH)
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge).build().toString();
    }

    String clear() {
        return ResponseCookie.from(NAME, "").httpOnly(true).secure(secure).sameSite("Strict").path(PATH)
                .maxAge(Duration.ZERO).build().toString();
    }
}
