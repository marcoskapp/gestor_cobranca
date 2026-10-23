package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.domain.model.ActionToken;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.identity.TenantProvisioned;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantSwitch;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
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

    private final TenantAdministration tenantAdministration;
    private final TenantSwitch tenantSwitch;
    private final RoleRepository roles;
    private final UserRepository users;
    private final CredentialService credentialService;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    TenantProvisioningService(TenantAdministration tenantAdministration, TenantSwitch tenantSwitch, RoleRepository roles,
                              UserRepository users, CredentialService credentialService, AccessScope accessScope,
                              AuditTrail auditTrail, ApplicationEventPublisher events, Clock clock) {
        this.tenantAdministration = tenantAdministration;
        this.tenantSwitch = tenantSwitch;
        this.roles = roles;
        this.users = users;
        this.credentialService = credentialService;
        this.accessScope = accessScope;
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
}
