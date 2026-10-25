package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
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
 * Perfis configuráveis, com hierarquia de administração:
 * <ul>
 *   <li>só se concede o que se tem (permissões novas ⊆ permissões de quem edita);</li>
 *   <li>só se edita ou desativa um perfil que se domina (permissões atuais ⊆ permissões de quem edita);</li>
 *   <li>o perfil {@code ADMIN} nunca perde o núcleo de governança e só fica abaixo do conjunto padrão
 *       por decisão de um administrador;</li>
 *   <li>códigos dos perfis padrão e de plataforma são reservados;</li>
 *   <li>quando um perfil perde permissões, as sessões de quem o tem são revogadas na hora (sem esperar
 *       o próximo refresh).</li>
 * </ul>
 * Tudo vai para a auditoria e para o log de acesso.
 */
@Service
@Transactional
class RoleManagementService implements RoleManagementUseCases {

    private final RoleRepository roles;
    private final UserRepository users;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final AdministrationHierarchy hierarchy;
    private final SessionManager sessionManager;

    /** O perfil ADMIN nunca perde estas permissões: sem elas ninguém mais administraria a assessoria. */
    static final Set<Permission> ADMIN_GOVERNANCE_CORE = EnumSet.of(Permission.USER_VIEW, Permission.USER_MANAGE,
            Permission.ROLE_MANAGE);

    /** Códigos que perfis da assessoria não podem usar: perfis padrão e o de plataforma. */
    static final Set<String> RESERVED_CODES = reservedCodes();

    RoleManagementService(RoleRepository roles, UserRepository users, CurrentActor currentActor, AuditTrail auditTrail,
                          AccessLogger accessLogger, AdministrationHierarchy hierarchy, SessionManager sessionManager) {
        this.roles = roles;
        this.users = users;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.hierarchy = hierarchy;
        this.sessionManager = sessionManager;
    }

    private static Set<String> reservedCodes() {
        Set<String> codes = new java.util.HashSet<>();
        DefaultRoles.forTenant().forEach(d -> codes.add(d.code()));
        codes.add(DefaultRoles.PLATFORM_ADMIN);
        return Set.copyOf(codes);
    }

    @Override
    public RoleView create(RoleCommand command) {
        String code = command.code() == null ? null : command.code().trim().toUpperCase();
        if (code != null && RESERVED_CODES.contains(code)) {
            accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, currentActor.get(),
                    Map.of("reason", "reserved_role_code", "roleCode", code)));
            throw new BusinessRuleException("role.code_reserved",
                    "Código reservado aos perfis padrão do sistema; escolha outro código");
        }
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
        Actor actor = currentActor.get();
        Role role = load(roleId);
        Set<Permission> permissions = parse(command.permissions());
        hierarchy.requireDominates(actor, role, "update_role");
        requireGrantable(permissions);
        if (isAdminRole(role)) {
            protectAdminRole(actor, permissions);
        }
        Set<Permission> removed = EnumSet.noneOf(Permission.class);
        removed.addAll(role.getPermissions());
        removed.removeAll(permissions);
        AuditEntry audit = AuditEntry.of("role.updated", "role", roleId)
                .change("name", role.getName(), command.name())
                .change("permissions", names(role.getPermissions()), names(permissions));
        role.update(command.name(), command.description(), permissions);
        roles.save(role);
        int revoked = removed.isEmpty() ? 0 : revokeHolders(role, "role_permissions_reduced");
        auditTrail.record(audit.meta("permissionsRemoved", names(removed)).meta("sessionsRevoked", revoked));
        logPermissionChange(role, permissions);
        return view(role);
    }

    private static boolean isAdminRole(Role role) {
        return role.isSystemRole() && DefaultRoles.ADMIN.equals(role.getCode());
    }

    /**
     * ADMIN: nunca sem o núcleo de governança; abaixo do conjunto padrão só por um administrador (perfil
     * ADMIN). Quem edita já domina o perfil (tem todas as permissões dele).
     */
    private void protectAdminRole(Actor actor, Set<Permission> permissions) {
        if (!permissions.containsAll(ADMIN_GOVERNANCE_CORE)) {
            throw new BusinessRuleException("role.admin_governance_required",
                    "O perfil Administrador deve manter a gestão de usuários e de perfis");
        }
        Set<Permission> baseline = DefaultRoles.forTenant().stream().filter(d -> DefaultRoles.ADMIN.equals(d.code()))
                .findFirst().map(DefaultRoles.Definition::permissions).orElse(Set.of());
        if (!permissions.containsAll(baseline) && !hierarchy.actorIsAdministrator(actor)) {
            accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor,
                    Map.of("reason", "admin_role_reduction_requires_admin")));
            throw new BusinessRuleException("role.admin_reduction_requires_admin",
                    "Somente um administrador pode retirar permissões padrão do perfil Administrador");
        }
    }

    /** Revoga as sessões de todos os usuários ativos com o perfil (a perda de permissão vale na hora). */
    private int revokeHolders(Role role, String reason) {
        int revoked = 0;
        for (var holder : users.findActiveByRoleIds(Set.of(role.getId()))) {
            revoked += sessionManager.revokeAll(holder.getId(), reason);
        }
        return revoked;
    }

    @Override
    public RoleView changeStatus(UUID roleId, boolean active) {
        Role role = load(roleId);
        hierarchy.requireDominates(currentActor.get(), role, active ? "activate_role" : "deactivate_role");
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
