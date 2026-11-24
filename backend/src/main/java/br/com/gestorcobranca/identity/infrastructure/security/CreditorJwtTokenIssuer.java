package br.com.gestorcobranca.identity.infrastructure.security;

import br.com.gestorcobranca.identity.application.port.out.CreditorTokenIssuer;
import br.com.gestorcobranca.identity.application.port.out.TokenIssuer.IssuedToken;
import br.com.gestorcobranca.identity.application.port.out.TokenIssuer.StepClaims;
import br.com.gestorcobranca.shared.security.JwtProperties;
import br.com.gestorcobranca.shared.security.TokenClaims;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Emissão e leitura dos tokens do portal do credor (ADR 0011), sempre com a audiência do credor. */
@Component
class CreditorJwtTokenIssuer implements CreditorTokenIssuer {

    private static final Set<String> STEP_TYPES = Set.of(TokenClaims.TYPE_CREDITOR_MFA, TokenClaims.TYPE_CREDITOR_PASSWORD);

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final JwtProperties properties;
    private final Clock clock;

    CreditorJwtTokenIssuer(JwtEncoder encoder, JwtDecoder decoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public IssuedToken issueAccessToken(UUID userId, UUID tenantId, UUID sessionId, UUID creditorId) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet set = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(TokenClaims.CREDITOR_AUDIENCE))
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .claim(TokenClaims.TYPE, TokenClaims.TYPE_CREDITOR)
                .claim(TokenClaims.TENANT, tenantId.toString())
                .claim(TokenClaims.SESSION, sessionId.toString())
                .claim(TokenClaims.CREDITOR, creditorId.toString())
                .build();
        return new IssuedToken(encode(set), expiresAt);
    }

    @Override
    public String issueStepToken(String type, UUID userId, UUID tenantId) {
        if (!STEP_TYPES.contains(type)) {
            throw new IllegalArgumentException("Tipo de etapa do credor inválido: " + type);
        }
        Instant now = Instant.now(clock);
        JwtClaimsSet set = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(TokenClaims.CREDITOR_AUDIENCE))
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.mfaTokenTtl()))
                .claim(TokenClaims.TYPE, type)
                .claim(TokenClaims.TENANT, tenantId.toString())
                .build();
        return encode(set);
    }

    @Override
    public Optional<StepClaims> readStepToken(String token, String expectedType) {
        if (token == null || token.isBlank() || !STEP_TYPES.contains(expectedType)) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(token);
            if (!expectedType.equals(jwt.getClaimAsString(TokenClaims.TYPE))
                    || !List.of(TokenClaims.CREDITOR_AUDIENCE).equals(jwt.getAudience())) {
                return Optional.empty();
            }
            return Optional.of(new StepClaims(UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString(TokenClaims.TENANT))));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
