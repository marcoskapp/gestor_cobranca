package br.com.gestorcobranca.negotiation;

import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ImportSummary;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.CreateUserCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases.CreatePolicyCommand;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyTerms;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.CreatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractTermsCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.CreateContractCommand;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.jobs.JobDrain;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.fail;

/**
 * Cenário comum dos testes de integração do módulo: assessoria provisionada (perfis de alçada
 * semeados pelo evento {@code TenantProvisioned}), credores A e B com carteiras operáveis,
 * operador X, gestor G e um devedor com títulos vencidos há 60 e 30 dias no credor A.
 */
abstract class NegotiationTestSupport extends IntegrationTest {

    static final String CPF = "529.982.247-25";

    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantProvisioning provisioning;
    @Autowired
    RoleManagementUseCases roles;
    @Autowired
    UserManagementUseCases userManagement;
    @Autowired
    CreditorUseCases creditors;
    @Autowired
    ServiceContractUseCases contracts;
    @Autowired
    PortfolioUseCases portfolios;
    @Autowired
    DebtIngestionPort ingestion;
    @Autowired
    CollectionPolicyUseCases policies;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    AuthorityUseCases authorityUseCases;
    @Autowired
    ApplicationContext applicationContext;

    UUID tenant;
    UUID admin;
    UUID creditorA;
    UUID creditorB;
    UUID portfolioA;
    UUID portfolioB;
    UUID operator;
    UUID manager;
    UUID debtor;
    UUID title60;
    UUID title30;
    LocalDate today;

    void provisionTenant(String prefix) {
        today = LocalDate.now(TimeConfig.BUSINESS_ZONE);
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.ProvisionedTenant provisioned = tenantExecution.callInTenant(Ids.newId(), "test.setup",
                () -> provisioning.provision(new ProvisionTenantCommand(slug, "Assessoria " + prefix + " Ltda", prefix,
                        "11.444.777/0001-61", "Admin", "admin@" + slug + ".test")));
        tenant = provisioned.tenant().id();
        admin = provisioned.adminUserId();
        creditorA = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81",
                null, null)).id());
        creditorB = system(() -> creditors.create(new CreditorCommand("Loja B S.A.", "Loja B", "45.997.418/0001-53",
                null, null)).id());
        portfolioA = portfolio(creditorA, "A-01");
        portfolioB = portfolio(creditorB, "B-01");
        Map<String, UUID> roleIds = system(() -> roles.list().stream()
                .collect(Collectors.toMap(RoleManagementUseCases.RoleView::code, RoleManagementUseCases.RoleView::id)));
        operator = user("Operador X", "x", roleIds.get(DefaultRoles.OPERADOR));
        manager = user("Gestor G", "g", roleIds.get(DefaultRoles.GESTOR));
        debtor = system(() -> ingestion.upsertDebtor(new DebtorRecord(CPF, "Maria Devedora", LocalDate.of(1985, 3, 15),
                java.util.List.of())).id());
        UUID batch = Ids.newId();
        title60 = system(() -> ingestion.upsertTitle(new TitleRecord(creditorA, portfolioA, debtor, "T-60", null, 1,
                today.minusDays(60), new BigDecimal("1000.00"), null, null), batch).id());
        title30 = system(() -> ingestion.upsertTitle(new TitleRecord(creditorA, portfolioA, debtor, "T-30", null, 2,
                today.minusDays(30), new BigDecimal("500.00"), null, null), batch).id());
        // fim da carga → PortfolioLoaded → caso de cobrança aberto pelo collection (elegibilidade da negociação)
        system(() -> {
            ingestion.completeImport(new ImportSummary(batch, creditorA, portfolioA, 2, 0, 1));
            return null;
        });
        awaitTrue("consumo do PortfolioLoaded", () -> count("select count(*) from shared.processing_job "
                + "where type = 'collection.portfolio_sync' and payload ->> 'importBatchId' = ?", batch.toString()) == 1);
        drainJobs("collection.");
        awaitTrue("caso de cobrança aberto", () -> count("select count(*) from collection.collection_case "
                + "where debtor_id = ? and portfolio_id = ?", debtor, portfolioA) == 1);
        awaitTrue("perfis de alçada semeados",
                () -> count("select count(*) from negotiation.authority_profile where system_profile") == 6);
    }

    void drainJobs(String typePrefix) {
        JobDrain.drain(applicationContext, jdbcTemplate, typePrefix);
    }

    UUID portfolio(UUID creditorId, String code) {
        return system(() -> {
            UUID contract = contracts.create(new CreateContractCommand(creditorId, "CT-" + code,
                    new ContractTermsCommand(LocalDate.of(2026, 1, 1), null, null, 10, null))).id();
            UUID id = portfolios.create(new CreatePortfolioCommand(creditorId, contract, code, "Carteira " + code, null,
                    LocalDate.of(2026, 1, 1), null)).id();
            return portfolios.changeStatus(id, "ACTIVE", null).id();
        });
    }

    private UUID user(String name, String key, UUID roleId) {
        return system(() -> userManagement.create(new CreateUserCommand(name, key + "-" + UUID.randomUUID().toString()
                .substring(0, 6) + "@teste.com", null, Set.of(roleId), true, Set.of())).id());
    }

    /** Publica a política (rascunho + publicação hoje) como gestor. */
    UUID publishPolicy(UUID creditorId, PolicyTerms terms) {
        return asManager(() -> {
            UUID id = policies.createDraft(new CreatePolicyCommand(creditorId, "Política " + creditorId, null, terms)).id();
            return policies.publish(id, today).id();
        });
    }

    /** Atribui ao usuário (para todos os credores) o perfil de alçada indicado, como administrador. */
    void assignProfile(UUID userId, String profileCode) {
        as(admin, DefaultRoles.ADMIN, CreditorScope.ALL, () -> {
            UUID profileId = authorityUseCases.listProfiles().stream().filter(p -> p.code().equals(profileCode))
                    .findFirst().orElseThrow().id();
            return authorityUseCases.assign(new AuthorityUseCases.AssignCommand("USER", userId, null, profileId));
        });
    }

    <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    <T> T asManager(Supplier<T> action) {
        return as(manager, DefaultRoles.GESTOR, CreditorScope.ALL, action);
    }

    <T> T asOperator(Supplier<T> action) {
        return as(operator, DefaultRoles.OPERADOR, CreditorScope.ALL, action);
    }

    <T> T as(UUID userId, String role, CreditorScope scope, Supplier<T> action) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst()
                .orElseThrow().permissions();
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), role, permissions,
                scope), action);
    }

    /** Assistente com as permissões mínimas da ferramenta de negociação, restrito ao credor da conversa. */
    <T> T asAssistant(UUID policyId, UUID creditorId, Supplier<T> action) {
        return tenantExecution.callAs(Actor.assistant(tenant, policyId, "padrao",
                EnumSet.of(Permission.NEGOTIATION_SIMULATE, Permission.POLICY_VIEW), Set.of(creditorId)), action);
    }

    long count(String sql, Object... args) {
        return system(() -> {
            Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
            return value == null ? 0L : value;
        });
    }

    static void awaitTrue(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrompido aguardando " + what);
            }
        }
        fail("tempo esgotado aguardando " + what);
    }
}
