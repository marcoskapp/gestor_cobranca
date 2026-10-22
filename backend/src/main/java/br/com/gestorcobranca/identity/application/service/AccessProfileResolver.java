package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Calcula as permissões efetivas e os perfis de um usuário a partir dos perfis ativos. */
@Component
class AccessProfileResolver {

    private final RoleRepository roles;

    AccessProfileResolver(RoleRepository roles) {
        this.roles = roles;
    }

    AccessProfile resolve(User user) {
        List<Role> active = roles.findAllByIdIn(user.getRoleIds()).stream().filter(Role::isActive).toList();
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
