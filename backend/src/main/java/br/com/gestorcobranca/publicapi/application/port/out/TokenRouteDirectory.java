package br.com.gestorcobranca.publicapi.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Rotas dos tokens (tabela global {@code publicapi.token_route}, sem RLS): resolvem a assessoria e o token pelo
 * prefixo antes de haver tenant na requisição. Contém só o prefixo e ids — o hash fica no token, protegido por RLS.
 */
public interface TokenRouteDirectory {

    void register(String prefix, UUID tenantId, UUID tokenId);

    Optional<Route> resolve(String prefix);

    record Route(UUID tenantId, UUID tokenId) {
    }
}
