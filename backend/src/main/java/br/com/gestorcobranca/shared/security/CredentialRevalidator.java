package br.com.gestorcobranca.shared.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Revalida, durante uma conexão de longa duração (stream SSE), a credencial que a autenticou:
 * assinatura e validade do token e estado da sessão no servidor (revogação, inatividade, expiração
 * absoluta), pelos mesmos {@link TokenActorResolver} da autenticação de cada requisição.
 * Autenticações sem token (jobs, testes) não são revalidáveis e contam como inválidas.
 */
@Component
public class CredentialRevalidator {

    private final JwtDecoder decoder;
    private final Map<String, TokenActorResolver> resolvers;

    CredentialRevalidator(JwtDecoder decoder, List<TokenActorResolver> resolvers) {
        this.decoder = decoder;
        this.resolvers = resolvers.stream()
                .collect(Collectors.toUnmodifiableMap(TokenActorResolver::tokenType, Function.identity()));
    }

    /** @return validade do token, se a credencial e a sessão continuam válidas */
    public Optional<Instant> validUntil(Authentication authentication) {
        if (!(authentication instanceof ActorAuthentication auth) || !(auth.getCredentials() instanceof String token)) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(token);
            TokenActorResolver resolver = resolvers.get(jwt.getClaimAsString(TokenClaims.TYPE));
            if (resolver == null) {
                return Optional.empty();
            }
            return resolver.resolve(jwt).filter(a -> a.id().equals(auth.actor().id())).map(a -> jwt.getExpiresAt());
        } catch (JwtException e) {
            return Optional.empty();
        }
    }
}
