package br.com.gestorcobranca.sandbox.domain;

import br.com.gestorcobranca.sandbox.domain.service.DemoTotp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TOTP próprio do sandbox: vetores do RFC 6238 (apêndice B, SHA-1) e a mesma chave em Base32 e hexadecimal. */
class DemoTotpTest {

    /** Semente do RFC 6238 para SHA-1: "12345678901234567890" (ASCII), em Base32. */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @ParameterizedTest
    @CsvSource({
            "59, 94287082",
            "1111111109, 07081804",
            "1111111111, 14050471",
            "1234567890, 89005924",
            "2000000000, 69279037",
            "20000000000, 65353130"})
    void rfc6238Sha1Vectors(long epochSecond, String expected8) throws Exception {
        long step = DemoTotp.step(Instant.ofEpochSecond(epochSecond));
        Method eight = DemoTotp.class.getDeclaredMethod("code", byte[].class, long.class, int.class);
        eight.setAccessible(true);
        assertThat(eight.invoke(null, "12345678901234567890".getBytes(StandardCharsets.US_ASCII), step, 8))
                .isEqualTo(expected8);
        // 6 dígitos = os 6 últimos do valor truncado (mesmo HOTP)
        assertThat(DemoTotp.code(RFC_SECRET, step)).isEqualTo(expected8.substring(2));
    }

    @Test
    void hexIsTheSameKeyAsBase32() {
        assertThat(DemoTotp.hex(RFC_SECRET)).isEqualTo("3132333435363738393031323334353637383930");
        assertThatThrownBy(() -> DemoTotp.hex("abc1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DemoTotp.hex(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stepAndSecondsLeft() {
        assertThat(DemoTotp.step(Instant.ofEpochSecond(29))).isZero();
        assertThat(DemoTotp.step(Instant.ofEpochSecond(30))).isEqualTo(1);
        assertThat(DemoTotp.secondsLeft(Instant.ofEpochSecond(30))).isEqualTo(30);
        assertThat(DemoTotp.secondsLeft(Instant.ofEpochSecond(59))).isEqualTo(1);
    }
}
