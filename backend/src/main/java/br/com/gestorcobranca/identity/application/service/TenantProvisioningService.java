package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.domain.model.ActionToken;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import br.com.gestorcobranca.identity.domain.model.UserStatus;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.identity.TenantProvisioned;
import br.com.gestorcobranca.shared.logging.SensitiveDataMasker;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Provisiona uma nova assessoria numa única transação: tenant (organization), perfis padrão e
 * administrador inicial (identity, já no contexto do novo tenant via {@link TenantSwitch}).
 * Publica {@link TenantProvisioned} para que outros módulos criem a configuração inicial da
 * assessoria (ex.: plano de comissão padrão) sem que este serviço dependa deles.
 */
@Service
class TenantProvisioningService implements TenantProvisioning {

    private static final Logger LOG = LoggerFactory.getLogger(TenantProvisioningService.class);

    private final TenantAdministration tenantAdministration;
    private final TenantSwitch tenantSwitch;
    private final RoleRepository roles;
    private final UserRepository users;
    private final CredentialService credentialService;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    TenantProvisioningService(TenantAdministration tenantAdministration, TenantSwitch tenantSwitch, RoleRepository roles,
                              UserRepository users, CredentialService credentialService, AccessScope accessScope,
                              CurrentActor currentActor, AuditTrail auditTrail, ApplicationEventPublisher events,
                              Clock clock) {
        this.tenantAdministration = tenantAdministration;
        this.tenantSwitch = tenantSwitch;
        this.roles = roles;
        this.users = users;
        this.credentialService = credentialService;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ProvisionedTenant provision(ProvisionTenantCommand command) {
        accessScope.requirePermission(Permission.TENANT_MANAGE);
        TenantView tenant = tenantAdministration.createTenant(new CreateTenantCommand(command.slug(), command.legalName(),
                command.tradeName(), command.cnpj()));
        UUID adminId = tenantSwitch.callAsSystemOf(tenant.id(), "identity.provisioning", () -> {
            Map<String, Role> created = DefaultRoles.forTenant().stream()
                    .map(def -> roles.save(new Role(def.code(), def.name(), def.description(), def.permissions(), true)))
                    .collect(Collectors.toMap(Role::getCode, r -> r));
            User admin = new User(command.adminName(), command.adminEmail(), null);
            admin.assignRoles(Set.of(created.get(DefaultRoles.ADMIN).getId()));
            admin.defineCreditorScope(true, Set.of());
            admin.requirePasswordChange();
            users.save(admin);
            auditTrail.record(AuditEntry.of("tenant.provisioned", "tenant", tenant.id())
                    .created("roles", String.join(",", created.keySet()))
                    .created("adminUserId", admin.getId()));
            credentialService.issueCredentialLink(admin, ActionToken.Purpose.ACTIVATION, tenant);
            events.publishEvent(new TenantProvisioned(Ids.newId(), tenant.id(), Instant.now(clock), admin.getId()));
            return admin.getId();
        });
        return new ProvisionedTenant(tenant, adminId);
    }

    @Override
    @Transactional
    public AdminInvitation resendAdminInvitation(UUID tenantId, String reason) {
        accessScope.requirePermission(Permission.TENANT_MANAGE);
        Actor platformActor = currentActor.get();
        TenantView tenant = tenantAdministration.findById(tenantId)
                .orElseThrow(() -> ResourceNotFoundException.of("tenant", tenantId));
        if (tenant.platform()) {
            throw new BusinessRuleException("tenant.platform_invitation",
                    "Administradores da plataforma são gerenciados em Administração → Usuários; o convite expirado "
                            + "também é renovado por \"Esqueci minha senha\"");
        }
        if (!tenant.active()) {
            throw new BusinessRuleException("tenant.inactive", "Reative a assessoria antes de reenviar o convite");
        }
        AdminInvitation invitation = tenantSwitch.callAsSystemOf(tenant.id(), "identity.admin_invitation", () -> {
            Role adminRole = roles.findByCode(DefaultRoles.ADMIN)
                    .orElseThrow(() -> new BusinessRuleException("tenant.no_pending_admin",
                            "A assessoria não tem administrador aguardando ativação"));
            if (users.countActiveWithRole(adminRole.getId()) > 0) {
                throw new BusinessRuleException("tenant.admin_already_active",
                        "A assessoria já tem administrador ativo: o reenvio é feito por ele, em Administração → Usuários");
            }
            List<User> pending = users.findWithRoleAndStatus(adminRole.getId(), UserStatus.PENDING_ACTIVATION);
            if (pending.isEmpty()) {
                throw new BusinessRuleException("tenant.no_pending_admin",
                        "A assessoria não tem administrador aguardando ativação");
            }
            User admin = pending.getFirst();
            Instant expiresAt = credentialService.issueCredentialLink(admin, ActionToken.Purpose.ACTIVATION, tenant);
            // trilha da assessoria: a intervenção da plataforma fica visível para os auditores dela
            auditTrail.record(AuditEntry.of("user.admin_invitation_resent", "user", admin.getId())
                    .reason(reason)
                    .meta("requestedByPlatformUserId", platformActor.id())
                    .meta("expiresAt", expiresAt));
            return new AdminInvitation(tenant.id(), admin.getId(), SensitiveDataMasker.maskEmail(admin.getEmail()),
                    expiresAt);
        });
        // trilha da plataforma: quem reenviou, para qual assessoria e por quê
        auditTrail.record(AuditEntry.of("tenant.admin_invitation_resent", "tenant", tenant.id())
                .reason(reason)
                .meta("adminUserId", invitation.adminUserId())
                .meta("expiresAt", invitation.expiresAt()));
        LOG.info("Convite do administrador inicial reenviado pela plataforma (assessoria {}, usuário {})",
                tenant.id(), invitation.adminUserId());
        return invitation;
    }
}
