package br.com.gestorcobranca.scoring;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueCommand;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ImportSummary;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.TeamUseCases;
import br.com.gestorcobranca.identity.application.port.in.TeamUseCases.TeamCommand;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.CreateUserCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.CreatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractTermsCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.CreateContractCommand;
import br.com.gestorcobranca.scoring.application.port.in.ScoringSettingsUseCases;
import br.com.gestorcobranca.scoring.application.port.in.ScoringSettingsUseCases.LegalBasisCommand;
import br.com.gestorcobranca.scoring.application.port.in.ScoringSettingsUseCases.SettingsCommand;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.fail;

/**
 * Cenário dos testes de integração do scoring: assessoria provisionada, credores A e B com
 * carteiras ativas, operador X numa equipe com fila do credor A, operador Z restrito ao credor B,
 * dois gestores. Casos nascem como em produção (carga → {@code PortfolioLoaded} → sincronização).
 */
@Import(ScoringTestSupport.ClockConfig.class)
abstract class ScoringTestSupport extends IntegrationTest {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    static final String[] CPFS = {"543.231.948-97", "757.491.186-06", "252.760.189-87", "555.979.711-15",
            "471.049.746-01", "507.529.170-80"};

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        ShiftableClock scoringTestClock() {
            return new ShiftableClock();
        }
    }

    /** Relógio da aplicação que pode ser adiantado (fim da janela de desfecho). */
    static final class ShiftableClock extends Clock {

        private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

        void advance(Duration by) {
            offset.set(offset.get().plus(by));
        }

        void reset() {
            offset.set(Duration.ZERO);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            ShiftableClock self = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId other) {
                    return self.withZone(other);
                }

                @Override
                public Instant instant() {
                    return self.instant();
                }
            };
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset.get());
        }
    }

    @Autowired
    ShiftableClock clock;
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
    TeamUseCases teams;
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
    CaseWorkUseCases work;
    @Autowired
    QueueUseCases queues;
    @Autowired
    ScoringSettingsUseCases scoringSettings;
    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    JdbcTemplate jdbcTemplate;

    UUID tenant;
    UUID creditorA;
    UUID creditorB;
    UUID portfolioA;
    UUID portfolioB;
    UUID operatorX;
    UUID operatorZ;
    UUID manager;
    UUID manager2;
    UUID team;
    UUID queueA;

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
        operatorX = user("Operador X", "x", roleIds.get(DefaultRoles.OPERADOR), true, Set.of());
        operatorZ = user("Operador Z", "z", roleIds.get(DefaultRoles.OPERADOR), false, Set.of(creditorB));
        manager = user("Gestor G", "g", roleIds.get(DefaultRoles.GESTOR), true, Set.of());
        manager2 = user("Gestor H", "h", roleIds.get(DefaultRoles.GESTOR), true, Set.of());
        team = system(() -> teams.create(new TeamCommand("Equipe 1", null, manager, Set.of(operatorX))).id());
        queueA = asManager(manager, () -> queues.create(new QueueCommand("BANCO_A", "Fila A", null, 10, creditorA, null,
                null, null, null, null, null, null, "HIGHEST_SCORE", "PULL", null, Set.of(team))).id());
        awaitTrue("catálogo de tabulações", () -> count("select count(*) from collection.tabulation") >= 12);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** Base legal registrada e snapshot diário (para o teste não depender do dia da semana). */
    void enableScoring() {
        asManager(manager, () -> scoringSettings.registerLegalBasis(new LegalBasisCommand("LEGITIMATE_INTEREST",
                "LIA-2026-07 aprovada pelo encarregado")));
        asManager(manager, () -> scoringSettings.update(new SettingsCommand(30, 500, 50, 10, new BigDecimal("0.30"),
                BigDecimal.ONE, new BigDecimal("0.60"), 365, 1, 10, new BigDecimal("0.10"), new BigDecimal("0.25"),
                List.of(400, 250, 150, 75), new BigDecimal("0.30"), true)));
    }

    private UUID portfolio(UUID creditorId, String code) {
        UUID id = system(() -> {
            UUID contract = contracts.create(new CreateContractCommand(creditorId, "CT-" + code,
                    new ContractTermsCommand(LocalDate.of(2026, 1, 1), null, null, 10, null))).id();
            return portfolios.create(new CreatePortfolioCommand(creditorId, contract, code, "Carteira " + code, null,
                    LocalDate.of(2026, 1, 1), null)).id();
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

    UUID debtor(String cpf) {
        return system(() -> ingestion.upsertDebtor(new DebtorRecord(cpf, "Devedor " + cpf.substring(0, 3),
                LocalDate.of(1985, 3, 15), List.of(new ContactRecord("PHONE", "(11) 98765-4321", "MOBILE", true)))).id());
    }

    /** Carga de um título vencido do devedor na carteira, seguida da sincronização dos casos. */
    CaseView loadCase(UUID creditorId, UUID portfolioId, String cpf, String principal, int daysOverdue) {
        UUID debtorId = debtor(cpf);
        UUID batch = Ids.newId();
        system(() -> {
            ingestion.upsertTitle(new TitleRecord(creditorId, portfolioId, debtorId, "T-" + UUID.randomUUID()
                    .toString().substring(0, 8), null, 1, LocalDate.now(ZONE).minusDays(daysOverdue),
                    new BigDecimal(principal), null, null), batch);
            ingestion.completeImport(new ImportSummary(batch, creditorId, portfolioId, 1, 0, 1));
            return null;
        });
        awaitTrue("consumo do PortfolioLoaded", () -> count("select count(*) from shared.processing_job "
                + "where type = 'collection.portfolio_sync' and payload ->> 'importBatchId' = ?", batch.toString()) == 1);
        drain("collection.");
        return system(() -> cases.findActive(debtorId, portfolioId)).orElseThrow();
    }

    void drain(String prefix) {
        JobDrain.drain(applicationContext, jdbcTemplate, prefix);
    }

    <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    <T> T as(UUID userId, String role, CreditorScope scope, Supplier<T> action) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst()
                .orElseThrow().permissions();
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), role, permissions,
                scope), action);
    }

    <T> T asManager(UUID userId, Supplier<T> action) {
        return as(userId, DefaultRoles.GESTOR, CreditorScope.ALL, action);
    }

    <T> T asOperator(UUID userId, Supplier<T> action) {
        return as(userId, DefaultRoles.OPERADOR, userId.equals(operatorZ) ? CreditorScope.of(Set.of(creditorB))
                : CreditorScope.ALL, action);
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
