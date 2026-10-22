package br.com.gestorcobranca.shared.domain;

import java.util.Map;
import java.util.UUID;

/**
 * Recurso inexistente ou fora do escopo do usuário (HTTP 404). Para não revelar a existência
 * de dados de outro credor/tenant, o escopo negado também é reportado como "não encontrado".
 */
public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String resource, Object id) {
        super(resource + ".not_found", "%s não encontrado".formatted(resource), Map.of("resource", resource, "id", String.valueOf(id)));
    }

    public static ResourceNotFoundException of(String resource, UUID id) {
        return new ResourceNotFoundException(resource, id);
    }
}
