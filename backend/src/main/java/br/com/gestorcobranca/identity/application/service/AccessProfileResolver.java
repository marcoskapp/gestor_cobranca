package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Calcula as permissões efetivas e os perfis de um usuário a partir dos perfis ativos. */
@Component
class AccessProfileResolver {

    private final RoleRepository roles;

    AccessProfileResolver(RoleRepository roles) {
        this.roles = roles;
    }

    AccessProfile resolve(User user) {
        return profile(roles.findAllByIdIn(user.getRoleIds()));
    }

    /** Perfis de vários usuários com os perfis já lidos de uma vez (listas de atribuíveis, sem consulta por usuário). */
    AccessProfile resolve(User user, Map<UUID, Role> rolesById) {
        return profile(user.getRoleIds().stream().map(rolesById::get).filter(Objects::nonNull).toList());
    }

    private static AccessProfile profile(List<Role> roles) {
        List<Role> active = roles.stream().filter(Role::isActive).toList();
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        active.forEach(role -> permissions.addAll(role.getPermissions()));
        Set<String> codes = active.stream().map(Role::getCode).collect(Collectors.toUnmodifiableSet());
        return new AccessProfile(Set.copyOf(permissions), codes);
    }

    record AccessProfile(Set<Permission> permissions, Set<String> roleCodes) {

        Set<String> permissionNames() {
            return permissions.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
        }
    }
}
