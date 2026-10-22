package br.com.gestorcobranca.identity.domain.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OpaqueTokenTest {

    @Test
    void generatedTokenCarriesTenantAndParsesBack() {
        UUID tenant = UUID.randomUUID();
        OpaqueToken token = OpaqueToken.generate(tenant);
        assertThat(token.hash()).hasSize(64);
        assertThat(OpaqueToken.parse(token.value())).hasValueSatisfying(parsed -> {
            assertThat(parsed.tenantId()).isEqualTo(tenant);
            assertThat(parsed.hash()).isEqualTo(token.hash());
        });
    }

    @Test
    void rejectsMalformedTokens() {
        assertThat(OpaqueToken.parse(null)).isEmpty();
        assertThat(OpaqueToken.parse("semponto")).isEmpty();
        assertThat(OpaqueToken.parse("abc.def")).isEmpty();
        assertThat(OpaqueToken.parse("x".repeat(300))).isEmpty();
    }

    @Test
    void toStringDoesNotLeakSecret() {
        OpaqueToken token = OpaqueToken.generate(UUID.randomUUID());
        assertThat(token.toString()).doesNotContain(token.value());
    }
}
