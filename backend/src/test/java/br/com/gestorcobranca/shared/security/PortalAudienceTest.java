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

    @Test
    void creditorTokensOnlyWithCreditorAudience() {
        for (String type : List.of(TokenClaims.TYPE_CREDITOR, TokenClaims.TYPE_CREDITOR_MFA,
                TokenClaims.TYPE_CREDITOR_MFA_ENROLL, TokenClaims.TYPE_CREDITOR_PASSWORD)) {
            assertThat(JwtKeyConfig.audienceMatchesType(jwt(type, List.of(TokenClaims.CREDITOR_AUDIENCE)))).as(type).isTrue();
            assertThat(JwtKeyConfig.audienceMatchesType(jwt(type, List.of(TokenClaims.AUDIENCE)))).as(type).isFalse();
            assertThat(JwtKeyConfig.audienceMatchesType(jwt(type, List.of(TokenClaims.PORTAL_AUDIENCE)))).as(type).isFalse();
        }
        // a audiência do credor nunca vale para o usuário interno, o devedor ou as etapas do login interno
        for (String type : List.of(TokenClaims.TYPE_ACCESS, TokenClaims.TYPE_PORTAL, TokenClaims.TYPE_MFA,
                TokenClaims.TYPE_MFA_ENROLL, TokenClaims.TYPE_PASSWORD_CHANGE)) {
            assertThat(JwtKeyConfig.audienceMatchesType(jwt(type, List.of(TokenClaims.CREDITOR_AUDIENCE)))).as(type).isFalse();
        }
    }

    @Test
    void unknownTokenTypeIsRejectedWithAnyAudience() {
        for (String audience : List.of(TokenClaims.AUDIENCE, TokenClaims.PORTAL_AUDIENCE, TokenClaims.CREDITOR_AUDIENCE)) {
            assertThat(JwtKeyConfig.audienceMatchesType(jwt("foo", List.of(audience)))).as(audience).isFalse();
            assertThat(JwtKeyConfig.audienceMatchesType(jwt("creditorx", List.of(audience)))).as(audience).isFalse();
        }
        assertThat(JwtKeyConfig.audienceMatchesType(jwt(null, List.of(TokenClaims.AUDIENCE)))).isFalse();
    }
}
