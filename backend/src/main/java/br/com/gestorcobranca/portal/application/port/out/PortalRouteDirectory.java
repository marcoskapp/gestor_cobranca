package br.com.gestorcobranca.portal.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Rotas públicas do portal (tabela global {@code portal.portal_route}, sem RLS): resolvem a
 * assessoria e a configuração a partir do endereço ({@code slug}) antes de existir tenant. Só o
 * slug e ids.
 */
public interface PortalRouteDirectory {

    /** Cria ou atualiza a rota da configuração (troca de endereço remove o anterior). */
    void upsert(String slug, UUID tenantId, UUID configurationId, boolean active);

    Optional<Route> resolve(String slug);

    boolean slugTaken(String slug, UUID exceptConfigurationId);

    record Route(String slug, UUID tenantId, UUID configurationId, boolean active) {
    }
}
