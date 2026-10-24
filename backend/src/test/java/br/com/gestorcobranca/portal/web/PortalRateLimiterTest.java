package br.com.gestorcobranca.portal.web;

import br.com.gestorcobranca.portal.application.PortalProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PortalRateLimiterTest {

    @Test
    void limitsEachIpPerMinute() {
        PortalProperties properties = new PortalProperties(null, null, new PortalProperties.Access(null, null, null, 3,
                null, null, null, null, null, null, null, null), null);
        PortalRateLimiter limiter = new PortalRateLimiter(properties,
                Clock.fixed(Instant.parse("2026-10-23T12:00:10Z"), ZoneOffset.UTC));
        assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
        assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
        assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
        assertThat(limiter.tryAcquire("10.0.0.1")).isFalse();
        assertThat(limiter.tryAcquire("10.0.0.2")).isTrue();
    }
}
