package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases;
import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Perfis configuráveis. Alterar permissões de um perfil afeta os usuários no próximo refresh de
 * token (no máximo a validade do access token); por isso a alteração fica registrada na
 * auditoria e no log de acesso.
 */
@Service
@Transactional
class RoleManagementService implements RoleManagementUseCases {

    private final RoleRepository roles;
    private final UserRepository users;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;

    RoleManagementService(RoleRepository roles, UserRepository users, CurrentActor currentActor, AuditTrail auditTrail,
                          AccessLogger accessLogger) {
        this.roles = roles;
        this.users = users;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
    }

    @Override
    public RoleView create(RoleCommand command) {
        String code = command.code() == null ? null : command.code().trim().toUpperCase();
        if (code != null && roles.existsByCode(code)) {
            throw new ConflictException("role.code_taken", "Já existe perfil com este código");
        }
        Set<Permission> permissions = parse(command.permissions());
        requireGrantable(permissions);
        Role role = roles.save(new Role(code, command.name(), command.description(), permissions, false));
        auditTrail.record(AuditEntry.of("role.created", "role", role.getId())
                .created("code", role.getCode()).created("permissions", names(permissions)));
        logPermissionChange(role, permissions);
        return view(role);
    }

    @Override
    public RoleView update(UUID roleId, RoleCommand command) {
        Role role = load(roleId);
        Set<Permission> permissions = parse(command.permissions());
        requireGrantable(permissions);
        AuditEntry audit = AuditEntry.of("role.updated", "role", roleId)
                .change("name", role.getName(), command.name())
                .change("permissions", names(role.getPermissions()), names(permissions));
        role.update(command.name(), command.description(), permissions);
        roles.save(role);
        auditTrail.record(audit);
        logPermissionChange(role, permissions);
        return view(role);
    }

    @Override
    public RoleView changeStatus(UUID roleId, boolean active) {
        Role role = load(roleId);
        if (!active && users.countActiveWithRole(roleId) > 0) {
            throw new BusinessRuleException("role.in_use", "Perfil atribuído a usuários ativos não pode ser desativado");
        }
        boolean previous = role.isActive();
        if (active) {
            role.activate();
        } else {
            role.deactivate();
        }
        roles.save(role);
        auditTrail.record(AuditEntry.of("role.status_changed", "role", roleId).change("active", previous, active));
        return view(role);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleView> list() {
        return roles.findAll().stream().sorted(Comparator.comparing(Role::getName)).map(RoleManagementService::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RoleView get(UUID roleId) {
        return view(load(roleId));
    }

    @Override
    public List<PermissionView> permissionCatalog() {
        Actor actor = currentActor.get();
        return Arrays.stream(Permission.values())
                .filter(p -> p != Permission.TENANT_MANAGE)
                .map(p -> new PermissionView(p.name(), p.description(), actor.isSystem() || actor.permissions().contains(p)))
                .toList();
    }

    /** Impede que alguém crie/edite um perfil com permissões que ele próprio não tem. */
    private void requireGrantable(Set<Permission> permissions) {
        Actor actor = currentActor.get();
        if (!actor.isSystem() && !actor.permissions().containsAll(permissions)) {
            Set<Permission> missing = EnumSet.noneOf(Permission.class);
            permissions.stream().filter(p -> !actor.permissions().contains(p)).forEach(missing::add);
            accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor,
                    Map.of("reason", "privilege_escalation_attempt", "permissions", names(missing))));
            // a lista é do próprio usuário (o que ele não tem); ajuda a corrigir a seleção na tela
            throw new BusinessRuleException("role.permission_not_grantable",
                    "Você só pode conceder permissões que o seu perfil possui. Fora do seu perfil: " + names(missing));
        }
    }

    private void logPermissionChange(Role role, Set<Permission> permissions) {
        accessLogger.log(AccessEvent.of(AccessEventType.PERMISSION_CHANGED, currentActor.get(),
                Map.of("roleId", role.getId().toString(), "roleCode", role.getCode(), "permissions", names(permissions))));
    }

    private static Set<Permission> parse(Set<String> values) {
        if (values == null || values.isEmpty()) {
            throw new BusinessRuleException("role.permissions_required", "Perfil deve ter ao menos uma permissão");
        }
        Set<Permission> result = EnumSet.noneOf(Permission.class);
        for (String value : values) {
            try {
                result.add(Permission.valueOf(value));
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException("role.permission_unknown", "Permissão desconhecida: " + value);
            }
        }
        return result;
    }

    private static String names(Set<Permission> permissions) {
        return permissions.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private Role load(UUID roleId) {
        return roles.findById(roleId).orElseThrow(() -> ResourceNotFoundException.of("role", roleId));
    }

    private static RoleView view(Role role) {
        return new RoleView(role.getId(), role.getCode(), role.getName(), role.getDescription(), role.isSystemRole(),
                role.isActive(), role.getPermissions().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()));
    }
}
