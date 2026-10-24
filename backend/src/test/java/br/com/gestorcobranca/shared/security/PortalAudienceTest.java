package br.com.gestorcobranca.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Tipo e audiência do token precisam corresponder: o token do portal nunca vale na API interna e vice-versa. */
class PortalAudienceTest {

    static Jwt jwt(String type, List<String> audience) {
        return Jwt.withTokenValue("t").header("alg", "RS256").claim(TokenClaims.TYPE, type).audience(audience)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    @Test
    void audienceMustMatchTokenType() {
        assertThat(JwtKeyConfig.audienceMatchesType(jwt(TokenClaims.TYPE_ACCESS, List.of(TokenClaims.AUDIENCE)))).isTrue();
        assertThat(JwtKeyConfig.audienceMatchesType(jwt(TokenClaims.TYPE_PORTAL, List.of(TokenClaims.PORTAL_AUDIENCE))))
                .isTrue();
        assertThat(JwtKeyConfig.audienceMatchesType(jwt(TokenClaims.TYPE_PORTAL, List.of(TokenClaims.AUDIENCE)))).isFalse();
        assertThat(JwtKeyConfig.audienceMatchesType(jwt(TokenClaims.TYPE_ACCESS, List.of(TokenClaims.PORTAL_AUDIENCE))))
                .isFalse();
        // as duas audiências juntas também não valem
        assertThat(JwtKeyConfig.audienceMatchesType(jwt(TokenClaims.TYPE_PORTAL,
                List.of(TokenClaims.PORTAL_AUDIENCE, TokenClaims.AUDIENCE)))).isFalse();
        assertThat(JwtKeyConfig.audienceMatchesType(jwt(TokenClaims.TYPE_MFA, List.of(TokenClaims.AUDIENCE)))).isTrue();
    }
}
