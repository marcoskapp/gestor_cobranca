package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases;
import br.com.gestorcobranca.identity.domain.model.ActionToken;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.model.UserStatus;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.organization.application.port.in.BusinessUnitUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
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
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.web.PageQuery;
import br.com.gestorcobranca.shared.web.PageResponse;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Administração de usuários com proteções contra escalada de privilégio:
 * <ul>
 *   <li>só é possível atribuir perfis cujas permissões o administrador também possua;</li>
 *   <li>só é possível conceder escopo de credores dentro do próprio escopo;</li>
 *   <li>não é possível desativar a si mesmo nem o último administrador ativo.</li>
 * </ul>
 */
@Service
@Transactional
class UserManagementService implements UserManagementUseCases {

    private final UserRepository users;
    private final RoleRepository roles;
    private final CredentialService credentialService;
    private final SessionManager sessionManager;
    private final CreditorUseCases creditors;
    private final BusinessUnitUseCases businessUnits;
    private final TenantAdministration tenantAdministration;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final Clock clock;

    UserManagementService(UserRepository users, RoleRepository roles, CredentialService credentialService,
                          SessionManager sessionManager, CreditorUseCases creditors, BusinessUnitUseCases businessUnits,
                          TenantAdministration tenantAdministration, CurrentActor currentActor, AuditTrail auditTrail,
                          AccessLogger accessLogger, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.credentialService = credentialService;
        this.sessionManager = sessionManager;
        this.creditors = creditors;
        this.businessUnits = businessUnits;
        this.tenantAdministration = tenantAdministration;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.clock = clock;
    }

    @Override
    public UserView create(CreateUserCommand command) {
        String email = User.normalizeEmail(command.email());
        if (users.existsByEmail(email)) {
            throw new ConflictException("user.email_taken", "Já existe usuário com este e-mail");
        }
        validateBusinessUnit(command.businessUnitId());
        List<Role> assigned = validateRoles(command.roleIds());
        validateCreditorScope(command.allCreditors(), command.creditorIds());

        User user = new User(command.name(), email, command.businessUnitId());
        user.assignRoles(command.roleIds());
        user.defineCreditorScope(command.allCreditors(), command.creditorIds());
        users.save(user);

        auditTrail.record(AuditEntry.of("user.created", "user", user.getId())
                .created("name", user.getName())
                .created("roles", codes(assigned))
                .created("allCreditors", user.isAllCreditors())
                .created("creditorIds", String.valueOf(user.getCreditorIds())));
        accessLogger.log(AccessEvent.of(AccessEventType.USER_CREATED, currentActor.get(),
                Map.of("targetUserId", user.getId().toString(), "roles", codes(assigned))));
        credentialService.issueCredentialLink(user, ActionToken.Purpose.ACTIVATION, tenantAdministration.currentTenant());
        return view(user);
    }

    @Override
    public UserView update(UUID userId, UpdateUserCommand command) {
        User user = load(userId);
        validateBusinessUnit(command.businessUnitId());
        AuditEntry audit = AuditEntry.of("user.updated", "user", userId)
                .change("name", user.getName(), command.name())
                .change("businessUnitId", user.getBusinessUnitId(), command.businessUnitId());
        user.rename(command.name());
        user.moveToBusinessUnit(command.businessUnitId());
        users.save(user);
        auditTrail.record(audit);
        accessLogger.log(AccessEvent.of(AccessEventType.USER_UPDATED, currentActor.get(), Map.of("targetUserId", userId.toString())));
        return view(user);
    }

    @Override
    public UserView assignRoles(UUID userId, Set<UUID> roleIds) {
        User user = load(userId);
        List<Role> assigned = validateRoles(roleIds);
        List<Role> previous = roles.findAllByIdIn(user.getRoleIds());
        boolean losesAdmin = previous.stream().anyMatch(r -> DefaultRoles.ADMIN.equals(r.getCode()))
                && assigned.stream().noneMatch(r -> DefaultRoles.ADMIN.equals(r.getCode()));
        if (losesAdmin) {
            requireAnotherActiveAdmin(user);
        }
        user.assignRoles(roleIds);
        users.save(user);
        sessionManager.revokeAll(userId, "roles_changed");
        auditTrail.record(AuditEntry.of("user.roles_changed", "user", userId)
                .change("roles", codes(previous), codes(assigned)));
        accessLogger.log(AccessEvent.of(AccessEventType.ROLE_CHANGED, currentActor.get(),
                Map.of("targetUserId", userId.toString(), "roles", codes(assigned))));
        return view(user);
    }

    @Override
    public UserView defineCreditorScope(UUID userId, boolean allCreditors, Set<UUID> creditorIds) {
        User user = load(userId);
        validateCreditorScope(allCreditors, creditorIds);
        AuditEntry audit = AuditEntry.of("user.scope_changed", "user", userId)
                .change("allCreditors", user.isAllCreditors(), allCreditors)
                .change("creditorIds", String.valueOf(user.getCreditorIds()), String.valueOf(creditorIds));
        user.defineCreditorScope(allCreditors, creditorIds);
        users.save(user);
        sessionManager.revokeAll(userId, "scope_changed");
        auditTrail.record(audit);
        accessLogger.log(AccessEvent.of(AccessEventType.SCOPE_CHANGED, currentActor.get(),
                Map.of("targetUserId", userId.toString(), "allCreditors", String.valueOf(allCreditors))));
        return view(user);
    }

    @Override
    public UserView disable(UUID userId, String reason) {
        Actor actor = currentActor.get();
        if (userId.equals(actor.id())) {
            throw new BusinessRuleException("user.cannot_disable_self", "Não é possível desativar o próprio usuário");
        }
        User user = load(userId);
        if (isAdmin(user)) {
            requireAnotherActiveAdmin(user);
        }
        UserStatus previous = user.getStatus();
        user.disable();
        users.save(user);
        int revoked = sessionManager.revokeAll(userId, "user_disabled");
        auditTrail.record(AuditEntry.of("user.disabled", "user", userId).change("status", previous, user.getStatus())
                .reason(reason).meta("sessionsRevoked", revoked));
        accessLogger.log(AccessEvent.of(AccessEventType.USER_DISABLED, actor, Map.of("targetUserId", userId.toString())));
        return view(user);
    }

    @Override
    public UserView enable(UUID userId) {
        User user = load(userId);
        UserStatus previous = user.getStatus();
        user.enable();
        users.save(user);
        auditTrail.record(AuditEntry.of("user.enabled", "user", userId).change("status", previous, user.getStatus()));
        accessLogger.log(AccessEvent.of(AccessEventType.USER_ENABLED, currentActor.get(), Map.of("targetUserId", userId.toString())));
        return view(user);
    }

    @Override
    public UserView unlock(UUID userId) {
        User user = load(userId);
        user.unlock();
        users.save(user);
        auditTrail.record(AuditEntry.of("user.unlocked", "user", userId));
        accessLogger.log(AccessEvent.of(AccessEventType.USER_UNLOCKED, currentActor.get(), Map.of("targetUserId", userId.toString())));
        return view(user);
    }

    @Override
    public UserView resetMfa(UUID userId, String reason) {
        User user = load(userId);
        user.resetMfa();
        users.save(user);
        sessionManager.revokeAll(userId, "mfa_reset");
        auditTrail.record(AuditEntry.of("user.mfa_reset", "user", userId).reason(reason));
        accessLogger.log(AccessEvent.of(AccessEventType.MFA_RESET, currentActor.get(), Map.of("targetUserId", userId.toString())));
        return view(user);
    }

    @Override
    public void sendCredentialLink(UUID userId) {
        User user = load(userId);
        ActionToken.Purpose purpose = switch (user.getStatus()) {
            case PENDING_ACTIVATION -> ActionToken.Purpose.ACTIVATION;
            case ACTIVE -> ActionToken.Purpose.PASSWORD_RESET;
            case DISABLED -> throw new BusinessRuleException("user.disabled", "Usuário desativado");
        };
        credentialService.issueCredentialLink(user, purpose, tenantAdministration.currentTenant());
    }

    @Override
    @Transactional(readOnly = true)
    public UserView get(UUID userId) {
        return view(load(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserView> search(String text, String status, UUID roleId, UUID businessUnitId, PageQuery page) {
        UserStatus statusFilter = status == null || status.isBlank() ? null : UserStatus.valueOf(status);
        return PageResponse.of(users.search(text, statusFilter, roleId, businessUnitId, page.toPageable(Sort.by("name"))),
                this::view);
    }

    // ------------------------------------------------------------------ validações

    private List<Role> validateRoles(Set<UUID> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw new BusinessRuleException("user.role_required", "Informe ao menos um perfil");
        }
        List<Role> found = roles.findAllByIdIn(roleIds);
        if (found.size() != roleIds.size() || found.stream().anyMatch(r -> !r.isActive())) {
            throw new BusinessRuleException("user.role_invalid", "Perfil inexistente ou inativo");
        }
        Set<Permission> granted = new HashSet<>();
        found.forEach(r -> granted.addAll(r.getPermissions()));
        Actor actor = currentActor.get();
        if (!actor.isSystem() && !actor.permissions().containsAll(granted)) {
            accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor, Map.of("reason", "privilege_escalation_attempt")));
            throw new ScopeAccessDeniedException();
        }
        return found;
    }

    private void validateCreditorScope(boolean allCreditors, Set<UUID> creditorIds) {
        Actor actor = currentActor.get();
        if (allCreditors) {
            if (!actor.creditorScope().all()) {
                throw new ScopeAccessDeniedException();
            }
            return;
        }
        Set<UUID> requested = creditorIds == null ? Set.of() : creditorIds;
        if (!requested.stream().allMatch(actor.creditorScope()::includes)) {
            throw new ScopeAccessDeniedException();
        }
        if (creditors.getAll(requested).size() != requested.size()) {
            throw new BusinessRuleException("user.creditor_invalid", "Credor inexistente no escopo informado");
        }
    }

    private void validateBusinessUnit(UUID businessUnitId) {
        if (businessUnitId != null) {
            businessUnits.get(businessUnitId);
        }
    }

    private boolean isAdmin(User user) {
        return roles.findAllByIdIn(user.getRoleIds()).stream().anyMatch(r -> DefaultRoles.ADMIN.equals(r.getCode()));
    }

    private void requireAnotherActiveAdmin(User user) {
        Role admin = roles.findByCode(DefaultRoles.ADMIN)
                .orElseThrow(() -> new IllegalStateException("Perfil ADMIN ausente no tenant"));
        long activeAdmins = users.countActiveWithRole(admin.getId());
        boolean userIsActiveAdmin = user.getStatus() == UserStatus.ACTIVE && user.getRoleIds().contains(admin.getId());
        if (userIsActiveAdmin && activeAdmins <= 1) {
            throw new BusinessRuleException("user.last_admin", "A assessoria deve manter ao menos um administrador ativo");
        }
    }

    private User load(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ResourceNotFoundException.of("user", userId));
    }

    private static String codes(List<Role> list) {
        return list.stream().map(Role::getCode).sorted().collect(Collectors.joining(","));
    }

    private UserView view(User user) {
        Instant now = Instant.now(clock);
        Set<String> roleCodes = roles.findAllByIdIn(user.getRoleIds()).stream().map(Role::getCode)
                .collect(Collectors.toUnmodifiableSet());
        return new UserView(user.getId(), user.getName(), user.getEmail(), user.getStatus().name(), user.isLocked(now),
                user.getLockedUntil(), user.isMfaEnabled(), user.getLastLoginAt(), user.getPasswordChangedAt(),
                user.getBusinessUnitId(), user.getRoleIds(), roleCodes, user.isAllCreditors(), user.getCreditorIds(),
                user.getCreatedAt());
    }
}
