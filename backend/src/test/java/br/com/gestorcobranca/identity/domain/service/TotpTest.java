package br.com.gestorcobranca.identity.domain.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    /** Segredo do apêndice B da RFC 6238 (SHA-1). */
    private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesRfc6238TestVectors() {
        // valores de 8 dígitos da RFC truncados para 6 dígitos (módulo 10^6)
        assertThat(Totp.generate(RFC_KEY, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.generate(RFC_KEY, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.generate(RFC_KEY, 2000000000L / 30)).isEqualTo("279037");
    }

    @Test
    void base32RoundTrip() {
        String encoded = Totp.base32(RFC_KEY);
        assertThat(encoded).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.base32Decode(encoded)).isEqualTo(RFC_KEY);
    }

    @Test
    void verifiesWithinOneStepWindowAndReturnsStep() {
        String secret = Totp.generateSecret();
        Instant now = Instant.parse("2026-10-22T12:00:10Z");
        long step = now.getEpochSecond() / 30;
        String previous = Totp.generate(Totp.base32Decode(secret), step - 1);
        assertThat(Totp.verify(secret, previous, now)).hasValue(step - 1);
        String old = Totp.generate(Totp.base32Decode(secret), step - 3);
        assertThat(Totp.verify(secret, old, now)).isEmpty();
        assertThat(Totp.verify(secret, "12a456", now)).isEmpty();
    }
}
