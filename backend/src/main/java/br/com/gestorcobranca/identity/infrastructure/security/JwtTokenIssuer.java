package br.com.gestorcobranca.identity.infrastructure.security;

import br.com.gestorcobranca.identity.application.port.out.TokenIssuer;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class JwtTokenIssuer implements TokenIssuer {

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final JwtProperties properties;
    private final Clock clock;

    JwtTokenIssuer(JwtEncoder encoder, JwtDecoder decoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public IssuedToken issueAccessToken(AccessClaims claims) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet set = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(TokenClaims.AUDIENCE))
                .subject(claims.userId().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .claim(TokenClaims.TYPE, TokenClaims.TYPE_ACCESS)
                .claim(TokenClaims.TENANT, claims.tenantId().toString())
                .claim(TokenClaims.SESSION, claims.sessionId().toString())
                .claim(TokenClaims.NAME, claims.name())
                .claim(TokenClaims.PERMISSIONS, claims.permissions().stream().sorted().toList())
                .claim(TokenClaims.ALL_CREDITORS, claims.allCreditors())
                .claim(TokenClaims.CREDITORS, claims.creditorIds().stream().map(UUID::toString).sorted().toList())
                .build();
        return new IssuedToken(encode(set), expiresAt);
    }

    @Override
    public String issueStepToken(String type, UUID userId, UUID tenantId, Duration ttl) {
        Instant now = Instant.now(clock);
        JwtClaimsSet set = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(TokenClaims.AUDIENCE))
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim(TokenClaims.TYPE, type)
                .claim(TokenClaims.TENANT, tenantId.toString())
                .build();
        return encode(set);
    }

    @Override
    public Optional<StepClaims> readStepToken(String token, String expectedType) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(token);
            if (!expectedType.equals(jwt.getClaimAsString(TokenClaims.TYPE))) {
                return Optional.empty();
            }
            return Optional.of(new StepClaims(UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString(TokenClaims.TENANT))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
