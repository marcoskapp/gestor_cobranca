package br.com.gestorcobranca.portal.infrastructure.security;

import br.com.gestorcobranca.portal.application.PortalProperties;
import br.com.gestorcobranca.portal.application.port.out.PortalTokenIssuer;
import br.com.gestorcobranca.shared.security.JwtProperties;
import br.com.gestorcobranca.shared.security.TokenClaims;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Token do portal: mesma infraestrutura de assinatura da plataforma ({@code shared.security}, RS256 com
 * {@code kid}), mas com {@code typ=portal}, audiência {@link TokenClaims#PORTAL_AUDIENCE} e nenhuma
 * permissão nos claims — as permissões do ator devedor são fixadas no servidor pelo resolvedor.
 */
@Component
class PortalJwtTokenIssuer implements PortalTokenIssuer {

    private final JwtEncoder encoder;
    private final JwtProperties jwt;
    private final PortalProperties properties;
    private final Clock clock;

    PortalJwtTokenIssuer(JwtEncoder encoder, JwtProperties jwt, PortalProperties properties, Clock clock) {
        this.encoder = encoder;
        this.jwt = jwt;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public IssuedToken issue(UUID tenantId, UUID sessionId, UUID debtorId, UUID creditorId) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(properties.session().tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .audience(List.of(TokenClaims.PORTAL_AUDIENCE))
                .subject(debtorId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .claim(TokenClaims.TYPE, TokenClaims.TYPE_PORTAL)
                .claim(TokenClaims.TENANT, tenantId.toString())
                .claim(TokenClaims.SESSION, sessionId.toString())
                .claim(TokenClaims.CREDITOR, creditorId.toString())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();
        return new IssuedToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
    }
}
