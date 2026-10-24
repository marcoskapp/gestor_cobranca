package br.com.gestorcobranca.settlement;

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
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.CollectionPolicyUseCases.CreatePolicyCommand;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PlanInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulateCommand;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.AcceptCommand;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.CreateProposalCommand;
import br.com.gestorcobranca.negotiation.domain.model.policy.StandardPolicyTerms;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.CreatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractTermsCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.CreateContractCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.EvidenceUpload;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases;
import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases;
import br.com.gestorcobranca.settlement.application.port.in.SettlementExportUseCases;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases;
import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.IntegrationEvent;
import br.com.gestorcobranca.shared.jobs.JobDrain;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.fail;

/**
 * Cenário comum dos testes de integração do módulo: assessoria provisionada; credor A (contrato e carteira A,
 * devedor com títulos de 60 e 30 dias, política de mercado) e credor B (contrato e carteira B, título avulso
 * de R$ 1.000,00 com 20 dias de atraso); operador X, gestor G (aprova demonstrativos), financeiros F1 e F2,
 * financeiro restrito ao credor B e o administrador. Pagamentos, acordos e conciliação passam pelos módulos
 * reais; o relógio pode ser deslocado para calcular um período que termina hoje.
 */
@Import(SettlementTestSupport.ClockConfig.class)
abstract class SettlementTestSupport extends IntegrationTest {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        SettlementClock settlementTestClock() {
            return new SettlementClock();
        }
    }

    static final String CPF = "529.982.247-25";
    static final byte[] PDF = "%PDF-1.4\n% comprovante de teste\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    SettlementClock clock;
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
    NegotiationSimulationUseCases simulations;
    @Autowired
    ProposalUseCases proposals;
    @Autowired
    AgreementUseCases agreements;
    @Autowired
    PaymentUseCases payments;
    @Autowired
    ReconciliationUseCases reconciliation;
    @Autowired
    CommissionRuleUseCases ruleSets;
    @Autowired
    CommissionLedgerQueries ledger;
    @Autowired
    SettlementStatementUseCases statements;
    @Autowired
    SettlementExportUseCases exports;
    @Autowired
    TransferUseCases transfers;
    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    ApplicationContext applicationContext;

    UUID tenant;
    UUID admin;
    UUID creditorA;
    UUID creditorB;
    UUID contractA;
    UUID contractB;
    UUID portfolioA;
    UUID portfolioB;
    UUID operator;
    UUID manager;
    UUID finance1;
    UUID finance2;
    UUID financeB;
    UUID debtor;
    UUID title60;
    UUID title30;
    UUID titleB;
    LocalDate today;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    void provision(String prefix) {
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
        contractA = contract(creditorA, "A-01");
        contractB = contract(creditorB, "B-01");
        portfolioA = portfolio(creditorA, contractA, "A-01");
        portfolioB = portfolio(creditorB, contractB, "B-01");
        Map<String, UUID> roleIds = system(() -> roles.list().stream()
                .collect(Collectors.toMap(RoleManagementUseCases.RoleView::code, RoleManagementUseCases.RoleView::id)));
        operator = user("Operador X", "x", roleIds.get(DefaultRoles.OPERADOR), true, Set.of());
        manager = user("Gestor G", "g", roleIds.get(DefaultRoles.GESTOR), true, Set.of());
        finance1 = user("Financeiro 1", "f1", roleIds.get(DefaultRoles.FINANCEIRO), true, Set.of());
        finance2 = user("Financeiro 2", "f2", roleIds.get(DefaultRoles.FINANCEIRO), true, Set.of());
        financeB = user("Financeiro B", "fb", roleIds.get(DefaultRoles.FINANCEIRO), false, Set.of(creditorB));
        debtor = system(() -> ingestion.upsertDebtor(new DebtorRecord(CPF, "Maria Devedora", LocalDate.of(1985, 3, 15),
                List.of())).id());
        UUID batchA = Ids.newId();
        title60 = system(() -> ingestion.upsertTitle(new TitleRecord(creditorA, portfolioA, debtor, "T-60", null, 1,
                today.minusDays(60), new BigDecimal("1000.00"), null, null), batchA).id());
        title30 = system(() -> ingestion.upsertTitle(new TitleRecord(creditorA, portfolioA, debtor, "T-30", null, 2,
                today.minusDays(30), new BigDecimal("500.00"), null, null), batchA).id());
        UUID batchB = Ids.newId();
        titleB = system(() -> ingestion.upsertTitle(new TitleRecord(creditorB, portfolioB, debtor, "B-1", null, 1,
                today.minusDays(20), new BigDecimal("1000.00"), null, null), batchB).id());
        system(() -> {
            ingestion.completeImport(new ImportSummary(batchA, creditorA, portfolioA, 2, 0, 1));
            ingestion.completeImport(new ImportSummary(batchB, creditorB, portfolioB, 1, 0, 1));
            return null;
        });
        awaitTrue("sincronização das carteiras agendada", () -> count("select count(*) from shared.processing_job "
                + "where type = 'collection.portfolio_sync' and tenant_id = ?", tenant) == 2);
        drainJobs("collection.");
        awaitTrue("casos de cobrança abertos", () -> count("select count(*) from collection.collection_case "
                + "where debtor_id = ?", debtor) == 2);
        awaitTrue("perfis de alçada semeados",
                () -> count("select count(*) from negotiation.authority_profile where system_profile") == 6);
        as(manager, DefaultRoles.GESTOR, CreditorScope.ALL, () -> {
            UUID id = policies.createDraft(new CreatePolicyCommand(creditorA, "Política A", null,
                    StandardPolicyTerms.marketDefault())).id();
            return policies.publish(id, today).id();
        });
    }

    /** Acordo dentro da alçada do operador (nível 0), aceito e formalizado. */
    AgreementView formalizeAgreement(int installments) {
        UUID simulation = asOperator(() -> simulations.simulate(new SimulateCommand(creditorA, debtor, List.of(), null,
                null, null, List.of(new PlanInput(installments, null, null)), "PHONE")).id());
        UUID proposal = asOperator(() -> proposals.create(new CreateProposalCommand(simulation, 0, null,
                new PlanInput(installments, null, null), null, null, false, null)).id());
        asOperator(() -> proposals.accept(proposal, new AcceptCommand("PHONE", "call-1", null, null)));
        AgreementView agreement = asOperator(() -> proposals.formalize(proposal));
        awaitTrue("acordo conhecido pelo settlement", () -> count(
                "select count(*) from settlement.agreement_fact where agreement_id = ? and installment_count is not null",
                agreement.id()) == 1);
        return agreement;
    }

    AgreementView agreement(UUID id) {
        return asManager(() -> agreements.get(id));
    }

    EvidenceUpload pdf() {
        return new EvidenceUpload("comprovante.pdf", "application/pdf", PDF.length, new ByteArrayInputStream(PDF));
    }

    TransferUseCases.EvidenceUpload transferPdf() {
        return new TransferUseCases.EvidenceUpload("ted.pdf", "application/pdf", PDF.length,
                new ByteArrayInputStream(PDF));
    }

    void drainJobs(String typePrefix) {
        JobDrain.drain(applicationContext, jdbcTemplate, typePrefix);
    }

    void publish(IntegrationEvent event) {
        tenantExecution.inTenant(tenant, "test.publish", () -> publisher.publishEvent(event));
    }

    void awaitConsumed(String consumer, long expected) {
        awaitTrue("consumo por " + consumer, () -> count("select count(*) from shared.consumed_event where consumer = ? "
                + "and tenant_id = ?", consumer, tenant) >= expected);
    }

    UUID contract(UUID creditorId, String code) {
        return system(() -> contracts.create(new CreateContractCommand(creditorId, "CT-" + code,
                new ContractTermsCommand(LocalDate.of(2026, 1, 1), null, null, 1, null))).id());
    }

    UUID portfolio(UUID creditorId, UUID contractId, String code) {
        return system(() -> {
            UUID id = portfolios.create(new CreatePortfolioCommand(creditorId, contractId, code, "Carteira " + code, null,
                    LocalDate.of(2026, 1, 1), null)).id();
            return portfolios.changeStatus(id, "ACTIVE", null).id();
        });
    }

    private UUID user(String name, String key, UUID roleId, boolean all, Set<UUID> creditorIds) {
        return system(() -> userManagement.create(new CreateUserCommand(name, key + "-" + UUID.randomUUID().toString()
                .substring(0, 6) + "@teste.com", null, Set.of(roleId), all, creditorIds)).id());
    }

    <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    <T> T asOperator(Supplier<T> action) {
        return as(operator, DefaultRoles.OPERADOR, CreditorScope.ALL, action);
    }

    <T> T asManager(Supplier<T> action) {
        return as(manager, DefaultRoles.GESTOR, CreditorScope.ALL, action);
    }

    <T> T asFinance1(Supplier<T> action) {
        return as(finance1, DefaultRoles.FINANCEIRO, CreditorScope.ALL, action);
    }

    <T> T asFinance2(Supplier<T> action) {
        return as(finance2, DefaultRoles.FINANCEIRO, CreditorScope.ALL, action);
    }

    <T> T asFinanceB(Supplier<T> action) {
        return as(financeB, DefaultRoles.FINANCEIRO, CreditorScope.of(Set.of(creditorB)), action);
    }

    <T> T asAdmin(Supplier<T> action) {
        return as(admin, DefaultRoles.ADMIN, CreditorScope.ALL, action);
    }

    <T> T as(UUID userId, String role, CreditorScope scope, Supplier<T> action) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst()
                .orElseThrow().permissions();
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), role, permissions,
                scope), action);
    }

    long count(String sql, Object... args) {
        return system(() -> {
            Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
            return value == null ? 0L : value;
        });
    }

    BigDecimal decimal(String sql, Object... args) {
        return system(() -> jdbcTemplate.queryForObject(sql, BigDecimal.class, args));
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
