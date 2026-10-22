package br.com.gestorcobranca.shared.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

/**
 * Converte um JWT válido (assinatura, emissor e validade já verificados) em {@link Actor}, após
 * checar o estado da sessão no servidor. Cada tipo de token ({@code typ}) tem seu resolvedor:
 * {@code access} (usuários, módulo identity) e {@code portal} (devedores, módulo portal).
 * <p>
 * Retornar vazio significa sessão revogada/expirada: a requisição é tratada como não
 * autenticada (HTTP 401).
 */
public interface TokenActorResolver {

    String tokenType();

    Optional<Actor> resolve(Jwt jwt);
}
