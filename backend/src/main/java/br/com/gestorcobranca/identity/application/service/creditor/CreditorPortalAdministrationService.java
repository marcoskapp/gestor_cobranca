package br.com.gestorcobranca.identity.application.service.creditor;

import br.com.gestorcobranca.identity.application.port.in.CreditorPortalAdministration;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.model.UserStatus;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorPortalAccess;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorRole;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorUser;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorUserStatus;
import br.com.gestorcobranca.identity.domain.repository.CreditorAccountRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Administração do portal do credor pela assessoria (ADR 0011). Exige {@code CREDITOR_PORTAL_MANAGE} (ADMIN e GESTOR —
 * decisão 1394) e o credor no escopo de quem administra. O convite vai só por e-mail (decisão 1395); o e-mail convidado
 * não pode ser de usuário da assessoria.
 */
@Service
@Transactional
class CreditorPortalAdministrationService implements CreditorPortalAdministration {

    private final CreditorAccountRepository accounts;
    private final UserRepository internalUsers;
    private final CreditorSessions sessions;
    private final CreditorCredentials credentials;
    private final CreditorInviteRefusals refusals;
    private final CreditorUseCases creditors;
    private final TenantAdministration tenants;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final BusinessEventLogger businessEvents;
    private final Clock clock;

    CreditorPortalAdministrationService(CreditorAccountRepository accounts, UserRepository internalUsers,
                                        CreditorSessions sessions, CreditorCredentials credentials,
                                        CreditorInviteRefusals refusals,
                                        CreditorUseCases creditors, TenantAdministration tenants, AccessScope accessScope,
                                        CurrentActor currentActor, AuditTrail auditTrail, AccessLogger accessLogger,
                                        BusinessEventLogger businessEvents, Clock clock) {
        this.accounts = accounts;
        this.internalUsers = internalUsers;
        this.sessions = sessions;
        this.credentials = credentials;
        this.refusals = refusals;
        this.creditors = creditors;
        this.tenants = tenants;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.businessEvents = businessEvents;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public CreditorPortalView status(UUID creditorId) {
        requireManage(creditorId);
        return view(creditorId);
    }

    @Override
    public CreditorPortalView enable(UUID creditorId) {
        requireManage(creditorId);
        creditors.requireActive(creditorId);
        CreditorPortalAccess access = accounts.findAccess(creditorId).orElseGet(() -> new CreditorPortalAccess(creditorId));
        if (access.enable(currentActor.actorId(), Instant.now(clock))) {
            accounts.saveAccess(access);
            auditTrail.record(AuditEntry.of("creditor_portal.enabled", "creditor", creditorId));
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.CREDITOR_PORTAL_TOGGLED).creditor(creditorId)
                    .attr("enabled", true));
        }
        return view(creditorId);
    }

    @Override
    public CreditorPortalView disable(UUID creditorId) {
        requireManage(creditorId);
        CreditorPortalAccess access = accounts.findAccess(creditorId).orElse(null);
        if (access != null && access.disable(currentActor.actorId(), Instant.now(clock))) {
            accounts.saveAccess(access);
            int revoked = sessions.revokeAllOfCreditor(creditorId, "portal_disabled");
            accounts.findUsersByCreditor(creditorId).forEach(credentials::invalidateLinks);
            auditTrail.record(AuditEntry.of("creditor_portal.disabled", "creditor", creditorId)
                    .meta("sessionsRevoked", revoked));
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.CREDITOR_PORTAL_TOGGLED).creditor(creditorId)
                    .attr("enabled", false));
        }
        return view(creditorId);
    }

    @Override
    public CreditorUserView invite(UUID creditorId, InviteCommand command) {
        requireManage(creditorId);
        Guard.require(command != null, "creditor_user.command_required", "Informe nome, e-mail e perfil");
        CreditorUseCases.CreditorView creditor = creditors.requireActive(creditorId);
        requireEnabled(creditorId);
        String email = User.normalizeEmail(command.email());
        if (internalUsers.findByEmail(email).filter(u -> u.getStatus() != UserStatus.DISABLED).isPresent()) {
            // a pessoa da assessoria não pode virar "o credor" (decisão 1395): a recusa é auditada em transação própria
            refusals.internalUserEmail(creditorId);
            throw new BusinessRuleException("creditor_user.internal_email",
                    "Este e-mail é de um usuário da assessoria. Convide a pessoa do credor pelo e-mail dela.");
        }
        if (accounts.existsUserWithEmail(email)) {
            throw new BusinessRuleException("creditor_user.email_taken",
                    "Já existe acesso ao portal do credor com este e-mail.");
        }
        CreditorUser user = accounts.saveUser(new CreditorUser(creditorId, command.name(), email, role(command.role()),
                currentActor.actorId(), Instant.now(clock)));
        credentials.issueInvitation(user, tenant(), displayName(creditor));
        auditTrail.record(AuditEntry.of("creditor_user.invited", "creditor_user", user.getId())
                .meta("creditorId", creditorId).meta("role", user.getRole()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.CREDITOR_USER_INVITED).creditor(creditorId)
                .attr("role", user.getRole().name()).attr("reinvite", false));
        return view(user);
    }

    @Override
    public CreditorUserView resendInvitation(UUID creditorId, UUID userId) {
        CreditorUser user = load(creditorId, userId);
        requireEnabled(creditorId);
        Guard.requireState(user.getStatus() == CreditorUserStatus.INVITED, "creditor_user.not_invited",
                "O convite só é reenviado para quem ainda não ativou o acesso. Para quem perdeu o autenticador, use "
                        + "\"Refazer acesso\".");
        credentials.issueInvitation(user, tenant(), displayName(creditors.requireActive(creditorId)));
        auditTrail.record(AuditEntry.of("creditor_user.invitation_resent", "creditor_user", user.getId()));
        return view(user);
    }

    @Override
    public CreditorUserView resetAccess(UUID creditorId, UUID userId) {
        CreditorUser user = load(creditorId, userId);
        requireEnabled(creditorId);
        user.resetToInvitation(currentActor.actorId(), Instant.now(clock));
        accounts.saveUser(user);
        int revoked = sessions.revokeAll(user.getId(), "access_reset");
        credentials.issueInvitation(user, tenant(), displayName(creditors.requireActive(creditorId)));
        auditTrail.record(AuditEntry.of("creditor_user.access_reset", "creditor_user", user.getId())
                .meta("sessionsRevoked", revoked));
        accessLogger.log(AccessEvent.of(AccessEventType.MFA_RESET, currentActor.get(),
                Map.of("creditorUserId", user.getId().toString())));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.CREDITOR_USER_INVITED).creditor(creditorId)
                .attr("role", user.getRole().name()).attr("reinvite", true));
        return view(user);
    }

    @Override
    public CreditorUserView changeRole(UUID creditorId, UUID userId, String role) {
        CreditorUser user = load(creditorId, userId);
        CreditorRole previous = user.getRole();
        user.changeRole(role(role));
        accounts.saveUser(user);
        auditTrail.record(AuditEntry.of("creditor_user.role_changed", "creditor_user", user.getId())
                .meta("from", previous).meta("to", user.getRole()));
        return view(user);
    }

    @Override
    public CreditorUserView revoke(UUID creditorId, UUID userId, String reason) {
        CreditorUser user = load(creditorId, userId);
        user.revoke(currentActor.actorId(), reason, Instant.now(clock));
        accounts.saveUser(user);
        int revoked = sessions.revokeAll(user.getId(), "access_revoked");
        credentials.invalidateLinks(user);
        auditTrail.record(AuditEntry.of("creditor_user.revoked", "creditor_user", user.getId())
                .meta("sessionsRevoked", revoked));
        accessLogger.log(AccessEvent.of(AccessEventType.SESSION_REVOKED, currentActor.get(),
                Map.of("creditorUserId", user.getId().toString(), "reason", "access_revoked")));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.CREDITOR_USER_REVOKED).creditor(creditorId)
                .attr("role", user.getRole().name()));
        return view(user);
    }

    // ------------------------------------------------------------------

    private void requireManage(UUID creditorId) {
        Actor actor = currentActor.get();
        if (!actor.isUser()) {
            // só a pessoa da assessoria administra o portal do credor (nenhum processo nem ator externo)
            throw accessScope.denyActorType("creditor_portal");
        }
        accessScope.requirePermission(Permission.CREDITOR_PORTAL_MANAGE);
        Guard.require(creditorId != null, "creditor.required", "Credor obrigatório");
        accessScope.requireCreditor(creditorId);
    }

    private void requireEnabled(UUID creditorId) {
        if (!accounts.findAccess(creditorId).map(CreditorPortalAccess::isEnabled).orElse(false)) {
            throw new BusinessRuleException("creditor_portal.disabled",
                    "Libere o portal do credor para este credor antes de convidar pessoas.");
        }
    }

    private CreditorUser load(UUID creditorId, UUID userId) {
        requireManage(creditorId);
        return accounts.findUser(userId).filter(u -> u.getCreditorId().equals(creditorId))
                .orElseThrow(() -> ResourceNotFoundException.of("creditor_user", userId));
    }

    private TenantView tenant() {
        return tenants.findById(currentActor.tenantId())
                .orElseThrow(() -> ResourceNotFoundException.of("tenant", currentActor.tenantId()));
    }

    private static String displayName(CreditorUseCases.CreditorView creditor) {
        return creditor.tradeName() != null && !creditor.tradeName().isBlank() ? creditor.tradeName() : creditor.legalName();
    }

    private static CreditorRole role(String code) {
        try {
            return CreditorRole.valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessRuleException("creditor_user.role_invalid", "Perfil inválido");
        }
    }

    private CreditorPortalView view(UUID creditorId) {
        CreditorPortalAccess access = accounts.findAccess(creditorId).orElse(null);
        List<CreditorUserView> users = accounts.findUsersByCreditor(creditorId).stream().map(this::view).toList();
        List<RoleOption> roles = Arrays.stream(CreditorRole.values()).map(r -> new RoleOption(r.name(), r.label())).toList();
        return new CreditorPortalView(creditorId, access != null && access.isEnabled(),
                access == null ? null : access.getEnabledAt(), users, roles);
    }

    private CreditorUserView view(CreditorUser user) {
        return new CreditorUserView(user.getId(), user.getName(), user.getEmail(), user.getRole().name(),
                user.getRole().label(), user.getStatus().name(), user.getInvitedAt(), user.getLastLoginAt(),
                user.getRevokedAt(), user.isLocked(Instant.now(clock)));
    }
}
