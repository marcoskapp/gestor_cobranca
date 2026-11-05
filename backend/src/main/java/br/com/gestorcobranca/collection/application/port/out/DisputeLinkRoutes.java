package br.com.gestorcobranca.collection.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Rotas dos links seguros das evidências enviadas ao credor (tabela global {@code collection.dispute_link_route},
 * sem RLS: o link chega sem assessoria). Guarda só o hash SHA-256 do token, ids e a validade.
 */
public interface DisputeLinkRoutes {

    void register(String tokenHash, UUID tenantId, UUID requestId, Instant expiresAt);

    Optional<Route> find(String tokenHash);

    /** Remove as rotas vencidas há mais de {@code graceDays}; @return quantidade removida */
    int purgeExpired(Instant before);

    record Route(UUID tenantId, UUID requestId, Instant expiresAt) {
    }
}
