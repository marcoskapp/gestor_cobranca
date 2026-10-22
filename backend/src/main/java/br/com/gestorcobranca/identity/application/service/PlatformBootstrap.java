package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.domain.model.ActionToken;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Garante, na inicialização, a existência do tenant da plataforma, do perfil PLATFORM_ADMIN e
 * do administrador inicial (que recebe link de ativação por e-mail). Idempotente: cada etapa
 * só é executada se ainda não tiver sido. Habilitado apenas quando
 * {@code app.bootstrap.platform-admin-email} estiver configurado.
 */
@Component
@ConditionalOnProperty(prefix = "app.bootstrap", name = "platform-admin-email")
class PlatformBootstrap implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformBootstrap.class);

    private final TenantAdministration tenantAdministration;
    private final TenantExecution tenantExecution;
    private final RoleRepository roles;
    private final UserRepository users;
    private final CredentialService credentialService;
    private final String adminEmail;
    private final String adminName;
    private final String platformCnpj;

    PlatformBootstrap(TenantAdministration tenantAdministration, TenantExecution tenantExecution, RoleRepository roles,
                      UserRepository users, CredentialService credentialService,
                      @Value("${app.bootstrap.platform-admin-email}") String adminEmail,
                      @Value("${app.bootstrap.platform-admin-name:Administrador da Plataforma}") String adminName,
                      @Value("${app.bootstrap.platform-cnpj}") String platformCnpj) {
        this.tenantAdministration = tenantAdministration;
        this.tenantExecution = tenantExecution;
        this.roles = roles;
        this.users = users;
        this.credentialService = credentialService;
        this.adminEmail = adminEmail;
        this.adminName = adminName;
        this.platformCnpj = platformCnpj;
    }

    @Override
    public void run(ApplicationArguments args) {
        TenantAdministration.PlatformTenant platform = tenantAdministration.ensurePlatformTenant(platformCnpj);
        TenantView tenant = platform.tenant();
        if (platform.created()) {
            LOG.warn("Tenant da plataforma criado (slug '{}')", tenant.slug());
        }
        tenantExecution.inTenant(tenant.id(), "identity.platform_bootstrap", () -> {
            Role role = roles.findByCode(DefaultRoles.PLATFORM_ADMIN).orElseGet(() -> {
                DefaultRoles.Definition def = DefaultRoles.platformAdmin();
                return roles.save(new Role(def.code(), def.name(), def.description(), def.permissions(), true));
            });
            String email = User.normalizeEmail(adminEmail);
            if (users.existsByEmail(email)) {
                return;
            }
            User admin = new User(adminName, email, null);
            admin.assignRoles(Set.of(role.getId()));
            admin.defineCreditorScope(true, Set.of());
            users.save(admin);
            credentialService.issueCredentialLink(admin, ActionToken.Purpose.ACTIVATION, tenant);
            LOG.warn("Administrador da plataforma {} criado; link de ativação enviado por e-mail", admin.getId());
        });
    }
}
