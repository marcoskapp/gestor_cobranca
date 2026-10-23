package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ImportSummary;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
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
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.CreateInstructionCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.InstructionView;
import br.com.gestorcobranca.payment.application.port.in.PaymentSandboxUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentSandboxUseCases.SimulatedNotification;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.EvidenceUpload;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases.AccountView;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases.CreateAccountCommand;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases.CredentialsCommand;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.jobs.JobDrain;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Cenário comum dos testes de integração do módulo: assessoria provisionada; credor A (carteira A,
 * devedor com títulos de 60 e 30 dias, política de mercado publicada) e credor B (carteira B, título
 * avulso de R$ 1.000,00 do mesmo devedor); operador X (todos os credores), operador B (só o credor B),
 * gestor G, financeiros F1 e F2 e o administrador; conta Pix SANDBOX da assessoria com webhook HMAC.
 */
abstract class PaymentTestSupport extends IntegrationTest {

    static final String CPF = "529.982.247-25";
    static final String WEBHOOK_SECRET = "segredo-hmac-de-teste-com-32-caracteres-ou-mais";
    static final byte[] PDF = "%PDF-1.4\n% comprovante de teste\n".getBytes(StandardCharsets.US_ASCII);

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
    DebtTitleUseCases debtTitles;
    @Autowired
    CollectionPolicyUseCases policies;
    @Autowired
    NegotiationSimulationUseCases simulations;
    @Autowired
    ProposalUseCases proposals;
    @Autowired
    AgreementUseCases agreements;
    @Autowired
    PixAccountUseCases pixAccounts;
    @Autowired
    PaymentInstructionUseCases instructions;
    @Autowired
    PaymentUseCases payments;
    @Autowired
    ReconciliationUseCases reconciliation;
    @Autowired
    PaymentSandboxUseCases sandbox;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    MeterRegistry meterRegistry;
    @Autowired
    ApplicationContext applicationContext;
    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    UUID tenant;
    UUID admin;
    UUID creditorA;
    UUID creditorB;
    UUID portfolioA;
    UUID portfolioB;
    UUID operator;
    UUID operatorB;
    UUID manager;
    UUID finance1;
    UUID finance2;
    UUID debtor;
    UUID title60;
    UUID title30;
    UUID titleB;
    LocalDate today;
    AccountView pixAccount;

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
        portfolioA = portfolio(creditorA, "A-01");
        portfolioB = portfolio(creditorB, "B-01");
        Map<String, UUID> roleIds = system(() -> roles.list().stream()
                .collect(Collectors.toMap(RoleManagementUseCases.RoleView::code, RoleManagementUseCases.RoleView::id)));
        operator = user("Operador X", "x", roleIds.get(DefaultRoles.OPERADOR), true, Set.of());
        operatorB = user("Operador B", "b", roleIds.get(DefaultRoles.OPERADOR), false, Set.of(creditorB));
        manager = user("Gestor G", "g", roleIds.get(DefaultRoles.GESTOR), true, Set.of());
        finance1 = user("Financeiro 1", "f1", roleIds.get(DefaultRoles.FINANCEIRO), true, Set.of());
        finance2 = user("Financeiro 2", "f2", roleIds.get(DefaultRoles.FINANCEIRO), true, Set.of());
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
                + "where type = 'collection.portfolio_sync'") == 2);
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
        pixAccount = asAdmin(() -> pixAccounts.create(new CreateAccountCommand(null, "PSP simulado", "SANDBOX",
                "pix@assessoria.test", "https://psp.sandbox.local/api/v2", "https://psp.sandbox.local/oauth/token",
                "HMAC", List.of(), new CredentialsCommand("client", "secret", WEBHOOK_SECRET, null, null))));
    }

    /** Acordo dentro da alçada do operador (nível 0), aceito e formalizado. */
    AgreementView formalizeAgreement(int installments) {
        UUID simulation = asOperator(() -> simulations.simulate(new SimulateCommand(creditorA, debtor, List.of(), null, null,
                null, List.of(new PlanInput(installments, null, null)), "PHONE")).id());
        UUID proposal = asOperator(() -> proposals.create(new CreateProposalCommand(simulation, 0, null,
                new PlanInput(installments, null, null), null, null, false, null)).id());
        asOperator(() -> proposals.accept(proposal, new AcceptCommand("PHONE", "call-1", null, null)));
        AgreementView agreement = asOperator(() -> proposals.formalize(proposal));
        awaitTrue("atribuição consumida pelo incentive", () -> count(
                "select count(*) from incentive.agreement_attribution where agreement_id = ?", agreement.id()) == 1);
        return agreement;
    }

    AgreementView agreement(UUID id) {
        return asManager(() -> agreements.get(id));
    }

    /** Instrução Pix registrada (job de registro executado). */
    InstructionView pixFor(UUID agreementId, UUID installmentId, String type) {
        InstructionView created = asOperator(() -> instructions.create(new CreateInstructionCommand(type, agreementId,
                installmentId, null, null, null, null, "WHATSAPP"), null));
        drainJobs("payment.");
        return asOperator(() -> instructions.get(created.id()));
    }

    /** PSP simulado registra o Pix e monta a notificação assinada (o teste a entrega por HTTP). */
    SimulatedNotification simulatePay(UUID instructionId, BigDecimal amount) {
        return asAdmin(() -> sandbox.simulatePixPayment(instructionId, amount, false));
    }

    SimulatedNotification simulateRefund(String endToEndId, BigDecimal amount) {
        return asAdmin(() -> sandbox.simulatePixRefund(endToEndId, amount, false));
    }

    /** Lançamentos no razão do título feitos pelo próprio payment (títulos avulsos). */
    double ledgerPostingsByPayment() {
        return meterRegistry.find("payment.ledger.postings").tag("operation", "payment").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    EvidenceUpload pdf() {
        return new EvidenceUpload("comprovante.pdf", "application/pdf", PDF.length, new ByteArrayInputStream(PDF));
    }

    int postWebhook(String routingKey, String body, String signature) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/webhooks/pix/" + routingKey + "/pix"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            request.header("X-Pix-Signature", signature);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    void drainJobs(String typePrefix) {
        JobDrain.drain(applicationContext, jdbcTemplate, typePrefix);
    }

    /** Espera o consumo de eventos e executa os jobs resultantes dos módulos envolvidos. */
    void settle() {
        drainJobs("payment.");
        drainJobs("negotiation.");
    }

    void awaitConsumed(String consumer, long expected) {
        awaitTrue("consumo por " + consumer, () -> count("select count(*) from shared.consumed_event where consumer = ?",
                consumer) >= expected);
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

    <T> T asOperatorB(Supplier<T> action) {
        return as(operatorB, DefaultRoles.OPERADOR, CreditorScope.of(Set.of(creditorB)), action);
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
