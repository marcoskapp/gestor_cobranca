package br.com.gestorcobranca.publicapi.domain;

import br.com.gestorcobranca.publicapi.domain.model.TokenSecret;
import br.com.gestorcobranca.publicapi.domain.service.TokenHasher;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenSecretAndHasherTest {

    private static final byte[] K1 = "chave-de-teste-com-32-bytes-ok!!".getBytes();
    private static final byte[] K2 = "outra-chave-de-teste-32-bytes-ok".getBytes();
    private final SecureRandom random = new SecureRandom();

    @Test
    void generatedTokenHasPublicPrefixAnd256BitSecretAndRoundTrips() {
        TokenSecret token = TokenSecret.generate(random);
        assertThat(token.value()).matches("gck_[a-z2-7]{12}\\.[A-Za-z0-9_-]{43}");
        assertThat(TokenSecret.parse(token.value())).contains(token);
        assertThat(token.toString()).doesNotContain(token.secret());
        assertThat(TokenSecret.generate(random).prefix()).isNotEqualTo(token.prefix());
    }

    @Test
    void malformedValuesAreRejectedWithoutDetails() {
        assertThat(TokenSecret.parse(null)).isEmpty();
        assertThat(TokenSecret.parse("gck_abc.def")).isEmpty();
        assertThat(TokenSecret.parse("Bearer gck_aaaaaaaaaaaa.xxx")).isEmpty();
        assertThat(TokenSecret.parse("x".repeat(500))).isEmpty();
    }

    @Test
    void hmacIsKeyedVersionedAndComparedInConstantTime() {
        TokenHasher hasher = new TokenHasher(Map.of("t1", K1, "t2", K2), "t1");
        TokenSecret token = TokenSecret.generate(random);
        String hash = hasher.hash(token);
        assertThat(hash).hasSize(64).isNotEqualTo(new TokenHasher(Map.of("t1", K2), "t1").hash(token));
        assertThat(hasher.matches("t1", hash, token)).isTrue();
        assertThat(hasher.matches("t2", hash, token)).isFalse();
        assertThat(hasher.matches("t9", hash, token)).isFalse();
        assertThat(hasher.matches("t1", hash, token.rotate(random))).isFalse();
    }

    @Test
    void shortOrMissingKeysFailAtStartup() {
        assertThatThrownBy(() -> new TokenHasher(Map.of("t1", new byte[16]), "t1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TokenHasher(Map.of(), "t1")).isInstanceOf(IllegalStateException.class);
    }
}
