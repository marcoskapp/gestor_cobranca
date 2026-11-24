package br.com.gestorcobranca.identity.infrastructure.security;

import br.com.gestorcobranca.identity.application.IdentityProperties;
import br.com.gestorcobranca.identity.domain.repository.SessionRepository;
import br.com.gestorcobranca.shared.security.TokenClaims;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Defesa em profundidade (ADR 0011): o resolvedor interno só aceita a audiência da API interna. */
class UserAccessTokenResolverAudienceTest {

    @Test
    void tokenDeAcessoComOutraAudienciaNaoViraAtorNemConsultaASessao() {
        SessionRepository sessions = mock(SessionRepository.class);
        TenantExecution tenantExecution = mock(TenantExecution.class);
        UserAccessTokenResolver resolver = new UserAccessTokenResolver(sessions, tenantExecution,
                mock(IdentityProperties.class), Clock.systemUTC());
        for (String audience : List.of(TokenClaims.PORTAL_AUDIENCE, TokenClaims.CREDITOR_AUDIENCE)) {
            Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(UUID.randomUUID().toString())
                    .claim(TokenClaims.TYPE, TokenClaims.TYPE_ACCESS).audience(List.of(audience))
                    .claim(TokenClaims.TENANT, UUID.randomUUID().toString())
                    .claim(TokenClaims.SESSION, UUID.randomUUID().toString())
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
            assertThat(resolver.resolve(jwt)).as(audience).isEmpty();
        }
        verifyNoInteractions(sessions, tenantExecution);
    }
}
