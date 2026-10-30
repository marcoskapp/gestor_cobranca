package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.collection.application.port.in.TabulationUseCases;
import br.com.gestorcobranca.debt.application.port.in.PrescriptionRuleUseCases;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases.RoleView;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.CreateUserCommand;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.UserView;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.sandbox.application.SandboxProperties;
import br.com.gestorcobranca.sandbox.application.port.out.ActivationMailbox;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.Person;
import br.com.gestorcobranca.sandbox.domain.service.FictitiousData;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Assessorias e usuários de demonstração. As assessorias nascem pelo provisionamento da plataforma (perfis padrão,
 * administrador inicial e configurações semeadas pelos consumidores de {@code TenantProvisioned}); cada usuário é
 * criado pela administração de usuários e ativado pelo fluxo real: link recebido por e-mail e definição da senha de
 * demonstração (política de senha, histórico, auditoria e log de acesso normais).
 */
@SandboxSeedComponent
class IdentitySeeder {

    private static final Logger LOG = LoggerFactory.getLogger(IdentitySeeder.class);
    static final String PLATFORM_SLUG = "plataforma";

    private final TenantAdministration tenants;
    private final TenantProvisioning provisioning;
    private final UserManagementUseCases users;
    private final RoleManagementUseCases roles;
    private final AuthenticationUseCases authentication;
    private final AuthorityUseCases authority;
    private final TabulationUseCases tabulations;
    private final PrescriptionRuleUseCases prescriptionRules;
    private final CommissionPlanUseCases commissionPlans;
    private final ActivationMailbox mailbox;
    private final SeedRuntime rt;
    private final SandboxProperties properties;

    IdentitySeeder(TenantAdministration tenants, TenantProvisioning provisioning, UserManagementUseCases users,
                   RoleManagementUseCases roles, AuthenticationUseCases authentication, AuthorityUseCases authority,
                   TabulationUseCases tabulations, PrescriptionRuleUseCases prescriptionRules,
                   CommissionPlanUseCases commissionPlans, ActivationMailbox mailbox, SeedRuntime rt,
                   SandboxProperties properties) {
        this.tenants = tenants;
        this.provisioning = provisioning;
        this.users = users;
        this.roles = roles;
        this.authentication = authentication;
        this.authority = authority;
        this.tabulations = tabulations;
        this.prescriptionRules = prescriptionRules;
        this.commissionPlans = commissionPlans;
        this.mailbox = mailbox;
        this.rt = rt;
        this.properties = properties;
        if (properties.seed().demoPassword() == null || properties.seed().demoPassword().isBlank()) {
            throw new IllegalStateException("app.sandbox.seed.demo-password é obrigatório com a carga do sandbox ligada");
        }
    }

    /** Ativa o administrador da plataforma criado pelo bootstrap padrão ({@code APP_BOOTSTRAP_PLATFORM_*}). */
    void activatePlatformAdmin() {
        TenantView platform = tenants.findBySlug(PLATFORM_SLUG).orElseThrow(() -> new IllegalStateException(
                "tenant da plataforma inexistente: configure APP_BOOTSTRAP_PLATFORM_ADMIN_EMAIL e APP_BOOTSTRAP_PLATFORM_CNPJ"));
        String email = properties.seed().platformAdminEmail();
        UserView admin = findUser(platform.id(), email).orElseThrow(() -> new IllegalStateException(
                "administrador da plataforma não encontrado (o bootstrap só cria o primeiro administrador)"));
        activate(platform.id(), admin, email);
    }

    /** Provisiona a assessoria (se ainda não existir) e espera a configuração inicial semeada pelos outros módulos. */
    void provisionTenant(TenantSeed seed) {
        Optional<TenantView> existing = tenants.findBySlug(seed.spec.slug());
        if (existing.isPresent()) {
            seed.tenantId = existing.get().id();
        } else {
            UUID platformId = tenants.findBySlug(PLATFORM_SLUG).orElseThrow().id();
            Person admin = person(seed, "ADMIN");
            String cnpj = new FictitiousData(seed.spec.seed() * 31).cnpj();
            seed.tenantId = rt.system(platformId, () -> provisioning.provision(new ProvisionTenantCommand(seed.spec.slug(),
                    seed.spec.legalName(), seed.spec.tradeName(), cnpj, admin.name(), admin.email(seed.spec)))).tenant().id();
            LOG.atInfo().addKeyValue("tenantId", seed.tenantId).addKeyValue("slug", seed.spec.slug())
                    .log("Assessoria do sandbox provisionada");
        }
        UUID tenantId = seed.tenantId;
        rt.await("perfis de alçada semeados", () -> rt.system(tenantId, () -> authority.listProfiles().stream()
                .anyMatch(p -> p.code().equals("PORTAL"))));
        rt.await("tabulações semeadas", () -> rt.system(tenantId, () -> tabulations.list(true).stream()
                .anyMatch(t -> t.code().equals("PROMISE"))));
        rt.await("regras de prescrição semeadas", () -> rt.system(tenantId, () -> !prescriptionRules.list().isEmpty()));
        rt.await("plano de comissão padrão", () -> rt.system(tenantId, () -> !commissionPlans.list().isEmpty()));
    }

    /** Ativa o administrador inicial criado no provisionamento (antes de cadastrar os credores). */
    void prepareAdmin(TenantSeed seed) {
        Person admin = person(seed, "ADMIN");
        Map<String, RoleView> byCode = roles(seed.tenantId);
        UserView view = findUser(seed.tenantId, admin.email(seed.spec)).orElseThrow(() -> new IllegalStateException(
                "administrador inicial da assessoria " + seed.spec.slug() + " não encontrado"));
        activate(seed.tenantId, view, admin.email(seed.spec));
        register(seed, admin, view, byCode);
    }

    /** Um usuário ativo para cada perfil padrão da assessoria (e o segundo operador, de escopo restrito). */
    void seedUsers(TenantSeed seed) {
        Map<String, RoleView> byCode = roles(seed.tenantId);
        Set<String> systemRoles = byCode.values().stream().filter(RoleView::systemRole).map(RoleView::code)
                .collect(Collectors.toSet());
        Set<String> covered = seed.spec.people().stream().map(Person::roleCode).collect(Collectors.toSet());
        if (!covered.containsAll(systemRoles)) {
            Set<String> missing = new java.util.TreeSet<>(systemRoles);
            missing.removeAll(covered);
            throw new IllegalStateException("catálogo do sandbox sem pessoa para os perfis padrão " + missing
                    + " (DemoCatalog)");
        }
        for (Person person : seed.spec.people()) {
            if (seed.actors.containsKey(person.key())) {
                continue;
            }
            RoleView role = byCode.get(person.roleCode());
            String email = person.email(seed.spec);
            boolean all = person.scope() == DemoCatalog.Scope.ALL_CREDITORS;
            Set<UUID> creditorIds = all ? Set.of() : Set.of(seed.creditorId(0));
            Instant before = rt.now();
            UserView view = findUser(seed.tenantId, email).orElseGet(() -> rt.system(seed.tenantId, () -> users.create(
                    new CreateUserCommand(person.name(), email, null, Set.of(role.id()), all, creditorIds))));
            activate(seed.tenantId, view, email, before);
            register(seed, person, rt.system(seed.tenantId, () -> users.get(view.id())), byCode);
        }
    }

    private void register(TenantSeed seed, Person person, UserView view, Map<String, RoleView> byCode) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        byCode.values().stream().filter(r -> view.roleIds().contains(r.id()))
                .forEach(r -> r.permissions().forEach(p -> permissions.add(Permission.valueOf(p))));
        CreditorScope scope = view.allCreditors() ? CreditorScope.ALL : CreditorScope.of(view.creditorIds());
        // sem sessão HTTP: a trilha de auditoria registra o usuário com sessão vazia e o nome marca a carga do sandbox
        seed.actors.put(person.key(), new Actor(Actor.ActorType.USER, view.id(), seed.tenantId, null,
                view.name() + " (carga do sandbox)", permissions, scope));
        seed.users.put(person.key(), view.id());
    }

    private void activate(UUID tenantId, UserView view, String email) {
        activate(tenantId, view, email, view.createdAt() == null ? rt.now() : view.createdAt());
    }

    /**
     * Usuário pendente: lê o link de ativação no e-mail (já enviado na criação, ou reenviado se a carga anterior foi
     * interrompida) e define a senha de demonstração. Usuário ativo: nada a fazer.
     */
    private void activate(UUID tenantId, UserView view, String email, Instant createdOrBefore) {
        if ("ACTIVE".equals(view.status())) {
            return;
        }
        if (!"PENDING_ACTIVATION".equals(view.status())) {
            throw new IllegalStateException("usuário de demonstração em situação inesperada: " + view.status());
        }
        Instant sentAfter = createdOrBefore;
        if (view.createdAt() != null && view.createdAt().isBefore(rt.now().minusSeconds(60))) {
            // carga anterior interrompida: novo link (o anterior é invalidado pelo próprio fluxo)
            sentAfter = rt.now();
            rt.system(tenantId, () -> {
                users.sendCredentialLink(view.id());
                return null;
            });
        }
        String token = mailbox.awaitCredentialToken(email, sentAfter, properties.seed().activationTimeout());
        authentication.completeCredentialAction(token, properties.seed().demoPassword());
        LOG.atInfo().addKeyValue("tenantId", tenantId).addKeyValue("userId", view.id())
                .log("Usuário de demonstração ativado pelo link de ativação");
    }

    private Optional<UserView> findUser(UUID tenantId, String email) {
        return rt.system(tenantId, () -> users.search(email, null, null, null, PageQuery.of(0, 10)).content().stream()
                .filter(u -> u.email().equalsIgnoreCase(email)).findFirst());
    }

    private Map<String, RoleView> roles(UUID tenantId) {
        List<RoleView> list = rt.system(tenantId, roles::list);
        return list.stream().collect(Collectors.toMap(RoleView::code, Function.identity(), (a, b) -> a));
    }

    private static Person person(TenantSeed seed, String roleCode) {
        return seed.spec.people().stream().filter(p -> p.roleCode().equals(roleCode)).findFirst().orElseThrow();
    }
}
