package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Hierarquia de administração: quem administra um usuário ou um perfil precisa <strong>dominá-lo</strong>
 * — ter todas as permissões dele e, para usuários, um escopo de credores que contenha o dele. Sem isso,
 * {@code USER_MANAGE}/{@code ROLE_MANAGE} permitiriam rebaixar, desativar ou redefinir o MFA de quem tem
 * mais poder (só se verificava o que era <em>concedido</em>). Atores de sistema dominam tudo.
 */
@Component
class AdministrationHierarchy {

    private final RoleRepository roles;
    private final UserRepository users;
    private final AccessLogger accessLogger;

    AdministrationHierarchy(RoleRepository roles, UserRepository users, AccessLogger accessLogger) {
        this.roles = roles;
        this.users = users;
        this.accessLogger = accessLogger;
    }

    /** Permissões efetivas do usuário: união das permissões dos perfis ativos atribuídos. */
    Set<Permission> effectivePermissions(User user) {
        return permissionsOf(roles.findAllByIdIn(user.getRoleIds()).stream().filter(Role::isActive).toList());
    }

    static Set<Permission> permissionsOf(Collection<Role> list) {
        Set<Permission> result = EnumSet.noneOf(Permission.class);
        list.forEach(r -> result.addAll(r.getPermissions()));
        return result;
    }

    /** O escopo do ator contém o escopo do usuário (todos os credores só é contido por todos os credores). */
    static boolean scopeCovers(CreditorScope actorScope, User user) {
        if (actorScope.all()) {
            return true;
        }
        return !user.isAllCreditors() && actorScope.creditorIds().containsAll(user.getCreditorIds());
    }

    boolean dominates(Actor actor, User target) {
        return actor.isSystem() || (actor.permissions().containsAll(effectivePermissions(target))
                && scopeCovers(actor.creditorScope(), target));
    }

    /** 403 (e {@code ACCESS_DENIED} no log de acesso) se o ator não domina o usuário-alvo. */
    void requireDominates(Actor actor, User target, String operation) {
        if (dominates(actor, target)) {
            return;
        }
        Set<Permission> missing = EnumSet.noneOf(Permission.class);
        effectivePermissions(target).stream().filter(p -> !actor.permissions().contains(p)).forEach(missing::add);
        accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor, Map.of(
                "reason", "target_outranks_actor", "operation", operation, "targetUserId", target.getId().toString(),
                "missingPermissions", names(missing),
                "scopeCovered", String.valueOf(scopeCovers(actor.creditorScope(), target)))));
        throw new ScopeAccessDeniedException();
    }

    /** 403 se o ator não tem todas as permissões atuais do perfil. */
    void requireDominates(Actor actor, Role role, String operation) {
        if (actor.isSystem() || actor.permissions().containsAll(role.getPermissions())) {
            return;
        }
        Set<Permission> missing = EnumSet.noneOf(Permission.class);
        role.getPermissions().stream().filter(p -> !actor.permissions().contains(p)).forEach(missing::add);
        accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor, Map.of(
                "reason", "role_outranks_actor", "operation", operation, "roleId", role.getId().toString(),
                "roleCode", role.getCode(), "missingPermissions", names(missing))));
        throw new ScopeAccessDeniedException();
    }

    boolean isAdministrator(User user) {
        return roles.findAllByIdIn(user.getRoleIds()).stream()
                .anyMatch(r -> r.isActive() && DefaultRoles.ADMIN.equals(r.getCode()) && r.isSystemRole());
    }

    /** O próprio ator é administrador da assessoria (perfil {@code ADMIN} de sistema); sistema sempre é. */
    boolean actorIsAdministrator(Actor actor) {
        if (actor.isSystem()) {
            return true;
        }
        if (!actor.isUser()) {
            return false;
        }
        return users.findById(actor.id()).map(this::isAdministrator).orElse(false);
    }

    static String names(Set<Permission> permissions) {
        return permissions.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    static Set<UUID> ids(Collection<Role> list) {
        return list.stream().map(Role::getId).collect(Collectors.toUnmodifiableSet());
    }
}
