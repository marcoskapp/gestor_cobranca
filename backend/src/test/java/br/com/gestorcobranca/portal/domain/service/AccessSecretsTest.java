package br.com.gestorcobranca.portal.domain.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AccessSecretsTest {

    @Test
    void codesAndTokensHaveTheExpectedShape() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String code = AccessSecrets.newCode();
            assertThat(AccessSecrets.isWellFormedCode(code)).isTrue();
            String token = AccessSecrets.newLinkToken();
            // sem dígitos: nunca se parece com telefone/documento nos filtros de dados pessoais
            assertThat(token).matches("[A-Za-z]{45}");
            assertThat(AccessSecrets.isWellFormedLinkToken(token)).isTrue();
            tokens.add(token);
            assertThat(AccessSecrets.newProtocol()).matches("[A-HJKMNP-Z2-9]{10}");
        }
        assertThat(tokens).hasSize(200);
        assertThat(AccessSecrets.isWellFormedCode("12345a")).isFalse();
        assertThat(AccessSecrets.isWellFormedLinkToken("abc")).isFalse();
        assertThat(AccessSecrets.isWellFormedLinkToken("A".repeat(44) + "1")).isFalse();
    }

    @Test
    void constantTimeComparison() {
        String h = AccessSecrets.sha256("x");
        assertThat(AccessSecrets.constantTimeEquals(h, AccessSecrets.sha256("x"))).isTrue();
        assertThat(AccessSecrets.constantTimeEquals(h, AccessSecrets.sha256("y"))).isFalse();
        assertThat(AccessSecrets.constantTimeEquals(h, null)).isFalse();
    }
}
