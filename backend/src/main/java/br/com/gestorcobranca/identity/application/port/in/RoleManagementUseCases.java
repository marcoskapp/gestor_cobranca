package br.com.gestorcobranca.identity.application.port.in;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Perfis e permissões configuráveis por assessoria. */
public interface RoleManagementUseCases {

    RoleView create(RoleCommand command);

    RoleView update(UUID roleId, RoleCommand command);

    RoleView changeStatus(UUID roleId, boolean active);

    List<RoleView> list();

    RoleView get(UUID roleId);

    /** Catálogo de permissões disponíveis, com descrição. */
    List<PermissionView> permissionCatalog();

    record RoleCommand(String code, String name, String description, Set<String> permissions) {
    }

    record RoleView(UUID id, String code, String name, String description, boolean systemRole, boolean active,
                    Set<String> permissions) {
    }

    record PermissionView(String code, String description) {
    }
}
