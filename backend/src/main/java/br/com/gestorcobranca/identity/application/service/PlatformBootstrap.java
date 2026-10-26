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
 * do <strong>primeiro</strong> administrador da plataforma (que recebe link de ativação por e-mail).
 * Habilitado apenas quando {@code app.bootstrap.platform-admin-email} estiver configurado (nas
 * instâncias da AWS, {@code APP_BOOTSTRAP_PLATFORM_ADMIN_EMAIL}, vindo da variável Terraform
 * {@code platform_admin_email}; ver decisão 500).
 * <p>
 * Idempotente e sem sobrescrita: o tenant da plataforma é criado uma única vez (o CNPJ configurado
 * não altera o cadastrado) e o administrador só é criado enquanto o perfil PLATFORM_ADMIN não tem
 * nenhum usuário, em qualquer situação. Trocar o e-mail configurado depois disso não cria outro
 * administrador nem altera o existente; novos administradores da plataforma são criados pela tela
 * de usuários, com auditoria. O link de ativação expirado é renovado pelo "Esqueci minha senha".
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
                      @Value("${app.bootstrap.platform-admin-name:}") String adminName,
                      @Value("${app.bootstrap.platform-cnpj:}") String platformCnpj) {
        if (adminEmail == null || adminEmail.isBlank()) {
            throw new IllegalStateException("app.bootstrap.platform-admin-email está vazio: informe o e-mail do primeiro "
                    + "administrador da plataforma ou remova a propriedade");
        }
        if (platformCnpj == null || platformCnpj.isBlank()) {
            throw new IllegalStateException("app.bootstrap.platform-cnpj é obrigatório junto com "
                    + "app.bootstrap.platform-admin-email (CNPJ do tenant da plataforma)");
        }
        this.tenantAdministration = tenantAdministration;
        this.tenantExecution = tenantExecution;
        this.roles = roles;
        this.users = users;
        this.credentialService = credentialService;
        this.adminEmail = adminEmail.trim();
        this.adminName = adminName == null || adminName.isBlank() ? "Administrador da Plataforma" : adminName.trim();
        this.platformCnpj = platformCnpj.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        TenantAdministration.PlatformTenant platform = tenantAdministration.ensurePlatformTenant(platformCnpj);
        TenantView tenant = platform.tenant();
        if (platform.created()) {
            LOG.warn("Tenant da plataforma criado (slug '{}')", tenant.slug());
        } else if (!digits(tenant.cnpj()).equals(digits(platformCnpj))) {
            LOG.info("Bootstrap da plataforma: o CNPJ configurado difere do cadastrado no tenant '{}'; mantido o cadastrado",
                    tenant.slug());
        }
        tenantExecution.inTenant(tenant.id(), "identity.platform_bootstrap", () -> {
            Role role = roles.findByCode(DefaultRoles.PLATFORM_ADMIN).orElseGet(() -> {
                DefaultRoles.Definition def = DefaultRoles.platformAdmin();
                return roles.save(new Role(def.code(), def.name(), def.description(), def.permissions(), true));
            });
            String email = User.normalizeEmail(adminEmail);
            if (users.countWithRole(role.getId()) > 0) {
                if (!users.existsByEmail(email)) {
                    LOG.info("Bootstrap da plataforma: já existe administrador da plataforma; o e-mail configurado "
                            + "não é usado (o bootstrap só cria o primeiro administrador)");
                }
                return;
            }
            if (users.existsByEmail(email)) {
                LOG.warn("Bootstrap da plataforma: o e-mail configurado pertence a um usuário da plataforma sem o perfil "
                        + "{}; nada foi alterado", DefaultRoles.PLATFORM_ADMIN);
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

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }
}
