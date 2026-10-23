package br.com.gestorcobranca.legal;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.CollateralAppraisalRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.CollateralRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContractNatureRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContractRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ImportSummary;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.CreateUserCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.legal.application.port.in.CreditorAuthorizationUseCases;
import br.com.gestorcobranca.legal.application.port.in.ExtrajudicialMeasureUseCases;
import br.com.gestorcobranca.legal.application.port.in.HearingUseCases;
import br.com.gestorcobranca.legal.application.port.in.LawFirmUseCases;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalDocumentUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalDocumentUseCases.DocumentView;
import br.com.gestorcobranca.legal.application.port.in.LegalDocumentUseCases.FileUpload;
import br.com.gestorcobranca.legal.application.port.in.LegalDocumentUseCases.UploadCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalEligibilityUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalExpenseUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalSettingsUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalSettingsUseCases.SettingsCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.CreatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractTermsCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.CreateContractCommand;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.IntegrationEvent;
import br.com.gestorcobranca.shared.jobs.JobDrain;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
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
 * Cenário comum dos testes do jurídico: assessoria provisionada (perfis padrão, inclusive
 * JURIDICO e ADVOGADO_PARCEIRO), credores A e B com carteiras ativas, regras de prescrição de
 * mercado, um advogado interno e um advogado de escritório parceiro (credor A). Casos de cobrança
 * nascem como em produção: carga de títulos → {@code PortfolioLoaded} → sincronização.
 */
abstract class LegalTestSupport extends IntegrationTest {

    static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    ApplicationContext applicationContext;
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
    CaseUseCases cases;
    @Autowired
    LegalSettingsUseCases settings;
    @Autowired
    LegalEligibilityUseCases eligibility;
    @Autowired
    LegalMatterUseCases matters;
    @Autowired
    LegalViabilityUseCases viability;
    @Autowired
    CreditorAuthorizationUseCases authorizations;
    @Autowired
    ExtrajudicialMeasureUseCases measures;
    @Autowired
    LawsuitUseCases lawsuits;
    @Autowired
    HearingUseCases hearings;
    @Autowired
    LegalDeadlineUseCases deadlines;
    @Autowired
    LegalExpenseUseCases expenses;
    @Autowired
    LawFirmUseCases firms;
    @Autowired
    LegalDocumentUseCases documents;
    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    JdbcTemplate jdbcTemplate;

    UUID tenant;
    UUID creditorA;
    UUID creditorB;
    UUID portfolioA;
    UUID portfolioB;
    UUID lawyer;
    UUID partner;
    UUID manager;

    void provision(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> provisioning.provision(
                new ProvisionTenantCommand(slug, "Assessoria " + prefix + " Ltda", prefix, "11.444.777/0001-61", "Admin",
                        "admin@" + slug + ".test"))).tenant().id();
        creditorA = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81",
                null, null)).id());
        creditorB = system(() -> creditors.create(new CreditorCommand("Loja B S.A.", "Loja B", "45.997.418/0001-53",
                null, null)).id());
        portfolioA = portfolio(creditorA, "A-01");
        portfolioB = portfolio(creditorB, "B-01");
        Map<String, UUID> roleIds = system(() -> roles.list().stream()
                .collect(Collectors.toMap(RoleManagementUseCases.RoleView::code, RoleManagementUseCases.RoleView::id)));
        lawyer = user("Jurídico J", "j", roleIds.get(DefaultRoles.JURIDICO), true, Set.of());
        partner = user("Advogado P", "p", roleIds.get(DefaultRoles.ADVOGADO_PARCEIRO), false, Set.of(creditorA));
        manager = user("Gestor G", "g", roleIds.get(DefaultRoles.GESTOR), true, Set.of());
        // regras de prescrição de mercado: semeadas pelo consumo de TenantProvisioned no debt
        awaitTrue("regras de prescrição", () -> count("select count(*) from shared.consumed_event "
                + "where consumer = 'debt.prescription-defaults' and tenant_id = ?", tenant) == 1);
    }

    private UUID portfolio(UUID creditorId, String code) {
        UUID id = system(() -> {
            UUID contract = contracts.create(new CreateContractCommand(creditorId, "CT-" + code,
                    new ContractTermsCommand(LocalDate.of(2019, 1, 1), null, null, 10, null))).id();
            return portfolios.create(new CreatePortfolioCommand(creditorId, contract, code, "Carteira " + code, null,
                    LocalDate.of(2019, 1, 1), null)).id();
        });
        system(() -> portfolios.changeStatus(id, "ACTIVE", "Início da operação"));
        return id;
    }

    private UUID user(String name, String key, UUID roleId, boolean allCreditors, Set<UUID> creditorIds) {
        UUID id = system(() -> userManagement.create(new CreateUserCommand(name, key + "-"
                + UUID.randomUUID().toString().substring(0, 6) + "@teste.com", null, Set.of(roleId), allCreditors,
                creditorIds)).id());
        system(() -> jdbcTemplate.update("update identity.app_user set status = 'ACTIVE' where id = ?", id));
        return id;
    }

    void allowLawFirms() {
        asManager(() -> settings.update(new SettingsCommand("BOTH", Set.of("CREDITOR", "LAW_FIRM"), "BLOCK", "EXCLUDE",
                "AUTHORIZATION", List.of(5, 2, 0), true, 5, 5, 10, 180, 180)));
    }

    // ------------------------------------------------------------------ dívidas e casos

    UUID debtor(String cpf) {
        return system(() -> ingestion.upsertDebtor(new DebtorRecord(cpf, "Devedor " + cpf.substring(0, 3),
                LocalDate.of(1985, 3, 15), List.of())).id());
    }

    record LoadedTitle(String externalId, String contract, LocalDate dueDate, String principal) {
    }

    /** Carga de títulos (com contratos) seguida do fim do lote e da sincronização dos casos. */
    List<UUID> load(UUID creditorId, UUID portfolioId, UUID debtorId, LoadedTitle... items) {
        UUID batch = Ids.newId();
        List<UUID> ids = system(() -> {
            List<UUID> created = new java.util.ArrayList<>();
            int n = 0;
            for (LoadedTitle t : items) {
                n++;
                created.add(ingestion.upsertTitle(new TitleRecord(creditorId, portfolioId, debtorId, t.externalId(),
                        new ContractRecord(t.contract(), "Financiamento", LocalDate.of(2019, 6, 1),
                                new BigDecimal("50000.00")), n, t.dueDate(), new BigDecimal(t.principal()), null, null),
                        batch).id());
            }
            ingestion.completeImport(new ImportSummary(batch, creditorId, portfolioId, items.length, 0, 1));
            return created;
        });
        awaitTrue("consumo do PortfolioLoaded", () -> count("select count(*) from shared.processing_job "
                + "where type = 'collection.portfolio_sync' and payload ->> 'importBatchId' = ?", batch.toString()) == 1);
        JobDrain.drain(applicationContext, jdbcTemplate, "collection.");
        return ids;
    }

    void executiveContract(UUID creditorId, String contract) {
        system(() -> ingestion.classifyContract(new ContractNatureRecord(creditorId, contract, "CCB", true)));
    }

    UUID vehicleCollateral(UUID creditorId, String contract, String value) {
        UUID id = system(() -> ingestion.upsertCollateral(new CollateralRecord(creditorId, contract, null,
                "VEHICLE_FIDUCIARY_ALIENATION", "Onix 2019", "VEHICLE", "ABC1D23", null, null, 2019, 2019, null, null,
                null, null, "DETRAN-SP", "G-1", LocalDate.of(2019, 6, 2), List.of())).id());
        system(() -> ingestion.recordCollateralAppraisal(new CollateralAppraisalRecord(id, new BigDecimal(value),
                LocalDate.of(2026, 9, 1), "FIPE", null), Ids.newId()));
        return id;
    }

    CaseView activeCase(UUID debtorId, UUID portfolioId) {
        return system(() -> cases.findActive(debtorId, portfolioId)).orElseThrow(() -> new AssertionError("sem caso ativo"));
    }

    DocumentView upload(UUID actor, String role, UUID matterId, String kind, String linkType, UUID linkId) {
        return as(actor, role, CreditorScope.ALL, () -> documents.upload(matterId, new UploadCommand(kind, linkType, linkId,
                "evidência"), new FileUpload("evidencia.pdf", "application/pdf", PDF.length,
                new ByteArrayInputStream(PDF))));
    }

    // ------------------------------------------------------------------ execução

    <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    <T> T as(UUID userId, String role, CreditorScope scope, Supplier<T> action) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst()
                .orElseThrow().permissions();
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), role, permissions,
                scope), action);
    }

    <T> T asLawyer(Supplier<T> action) {
        return as(lawyer, DefaultRoles.JURIDICO, CreditorScope.ALL, action);
    }

    <T> T asManager(Supplier<T> action) {
        return as(manager, DefaultRoles.GESTOR, CreditorScope.ALL, action);
    }

    <T> T asPartner(Supplier<T> action) {
        return as(partner, DefaultRoles.ADVOGADO_PARCEIRO, CreditorScope.of(Set.of(creditorA)), action);
    }

    void publishAndAwait(IntegrationEvent event, String consumer) {
        tenantExecution.inTenant(tenant, "test.publish", () -> publisher.publishEvent(event));
        awaitTrue("consumo por " + consumer, () -> {
            Long consumed = jdbcTemplate.queryForObject(
                    "select count(*) from shared.consumed_event where consumer = ? and event_id = ?", Long.class,
                    consumer, event.eventId());
            return consumed != null && consumed == 1;
        });
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
