package br.com.gestorcobranca.identity.web;

import br.com.gestorcobranca.identity.application.IdentityProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Só a primeira recusa do minuto é marcada para o log de acesso (achado B05). */
class LoginRateLimiterTest {

    @Test
    void primeiraRecusaDoMinutoEhAUnicaMarcada() {
        IdentityProperties properties = mock(IdentityProperties.class);
        when(properties.rateLimit()).thenReturn(new IdentityProperties.RateLimit(3));
        LoginRateLimiter limiter = new LoginRateLimiter(properties,
                Clock.fixed(Instant.parse("2026-11-24T12:00:10Z"), ZoneOffset.UTC));
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.acquire("creditor_login:1.2.3.4")).isEqualTo(LoginRateLimiter.Decision.ALLOWED);
        }
        assertThat(limiter.acquire("creditor_login:1.2.3.4")).isEqualTo(LoginRateLimiter.Decision.REJECTED_FIRST);
        assertThat(limiter.acquire("creditor_login:1.2.3.4")).isEqualTo(LoginRateLimiter.Decision.REJECTED);
        assertThat(limiter.tryAcquire("creditor_login:1.2.3.4")).isFalse();
        assertThat(limiter.acquire("creditor_login:5.6.7.8")).isEqualTo(LoginRateLimiter.Decision.ALLOWED);
    }
}
