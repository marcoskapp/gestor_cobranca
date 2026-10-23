package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases;
import br.com.gestorcobranca.collection.application.port.in.CollectionSettingsUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordInteractionCommand;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordResult;
import br.com.gestorcobranca.collection.application.port.in.PromiseUseCases;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueCommand;
import br.com.gestorcobranca.collection.application.port.in.TabulationUseCases;
import br.com.gestorcobranca.collection.application.port.in.TaskUseCases;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ImportSummary;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort;
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
 * Cenário comum dos testes de integração do módulo: assessoria provisionada (perfis padrão e
 * catálogo de tabulações semeado pelo consumo de {@code TenantProvisioned}), credores A e B com
 * carteiras, operadores X e Y numa equipe, supervisor S, operador Z restrito ao credor B. Os casos
 * nascem como em produção: carga de títulos → {@code PortfolioLoaded} → job de sincronização.
 */
@Import(CollectionTestSupport.ClockConfig.class)
abstract class CollectionTestSupport extends IntegrationTest {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        ShiftableClock collectionTestClock() {
            return new ShiftableClock();
        }
    }

    /** Relógio da aplicação que pode ser adiantado (jobs de vencimento); nulo = hora real. */
    static final class ShiftableClock extends Clock {

        private final AtomicReference<java.time.Duration> offset = new AtomicReference<>(java.time.Duration.ZERO);

        void advance(java.time.Duration by) {
            offset.set(offset.get().plus(by));
        }

        void reset() {
            offset.set(java.time.Duration.ZERO);
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
    DebtLedgerPort ledger;
    @Autowired
    ContactRestrictionUseCases restrictions;
    @Autowired
    CaseUseCases cases;
    @Autowired
    CaseWorkUseCases work;
    @Autowired
    QueueUseCases queues;
    @Autowired
    TabulationUseCases tabulations;
    @Autowired
    InteractionUseCases interactions;
    @Autowired
    TaskUseCases tasks;
    @Autowired
    PromiseUseCases promises;
    @Autowired
    DisputeUseCases disputes;
    @Autowired
    CollectionSettingsUseCases settings;
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
    UUID operatorY;
    UUID operatorZ;
    UUID supervisor;
    UUID team;

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
        operatorY = user("Operador Y", "y", roleIds.get(DefaultRoles.OPERADOR), true, Set.of());
        operatorZ = user("Operador Z", "z", roleIds.get(DefaultRoles.OPERADOR), false, Set.of(creditorB));
        supervisor = user("Supervisor S", "s", roleIds.get(DefaultRoles.SUPERVISOR), true, Set.of());
        team = system(() -> teams.create(new TeamCommand("Equipe 1", null, supervisor, Set.of(operatorX, operatorY))).id());
        awaitTrue("catálogo de tabulações", () -> count("select count(*) from collection.tabulation") >= 12);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** Carteira ativa (em operação). */
    private UUID portfolio(UUID creditorId, String code) {
        UUID id = draftPortfolio(creditorId, code);
        system(() -> portfolios.changeStatus(id, "ACTIVE", "Início da operação"));
        return id;
    }

    /** Carteira cadastrada e ainda não operada (DRAFT): casos inelegíveis. */
    UUID draftPortfolio(UUID creditorId, String code) {
        return system(() -> {
            UUID contract = contracts.create(new CreateContractCommand(creditorId, "CT-" + code,
                    new ContractTermsCommand(LocalDate.of(2026, 1, 1), null, null, 10, null))).id();
            return portfolios.create(new CreatePortfolioCommand(creditorId, contract, code, "Carteira " + code, null,
                    LocalDate.of(2026, 1, 1), null)).id();
        });
    }

    /** Usuário criado e ativado (como após o primeiro acesso), para entrar na distribuição. */
    private UUID user(String name, String key, UUID roleId, boolean allCreditors, Set<UUID> creditorIds) {
        UUID id = system(() -> userManagement.create(new CreateUserCommand(name, key + "-"
                + UUID.randomUUID().toString().substring(0, 6) + "@teste.com", null, Set.of(roleId), allCreditors,
                creditorIds)).id());
        system(() -> jdbcTemplate.update("update identity.app_user set status = 'ACTIVE' where id = ?", id));
        return id;
    }

    // ------------------------------------------------------------------ dívidas e casos

    UUID debtor(String cpf) {
        return system(() -> ingestion.upsertDebtor(new DebtorRecord(cpf, "Devedor " + cpf.substring(0, 3),
                LocalDate.of(1985, 3, 15), List.of(new ContactRecord("PHONE", "(11) 98765-4321", "MOBILE", true),
                new ContactRecord("EMAIL", "devedor" + cpf.substring(0, 3) + "@exemplo.com", null, false)))).id());
    }

    /** Carga de títulos de um devedor na carteira (lote próprio) seguida do fim do lote e da sincronização. */
    List<UUID> load(UUID creditorId, UUID portfolioId, UUID debtorId, String... principals) {
        UUID batch = Ids.newId();
        List<UUID> titleIds = system(() -> {
            List<UUID> ids = new java.util.ArrayList<>();
            int n = 0;
            for (String principal : principals) {
                n++;
                ids.add(ingestion.upsertTitle(new TitleRecord(creditorId, portfolioId, debtorId,
                        "T-" + UUID.randomUUID().toString().substring(0, 8), null, n, LocalDate.of(2026, 3, 10).plusDays(n),
                        new BigDecimal(principal), null, null), batch).id());
            }
            ingestion.completeImport(new ImportSummary(batch, creditorId, portfolioId, principals.length, 0, 1));
            return ids;
        });
        awaitTrue("consumo do PortfolioLoaded", () -> count("select count(*) from shared.processing_job "
                + "where type = 'collection.portfolio_sync' and payload ->> 'importBatchId' = ?", batch.toString()) == 1);
        drainJobs();
        return titleIds;
    }

    void drainJobs() {
        JobDrain.drain(applicationContext, jdbcTemplate, "collection.");
    }

    CaseView activeCase(UUID debtorId, UUID portfolioId) {
        return system(() -> cases.findActive(debtorId, portfolioId)).orElseThrow(() -> new AssertionError("sem caso ativo"));
    }

    QueueCommand queue(String code, int priority, UUID creditorId, String strategy, Integer capacity, Set<UUID> teamIds) {
        return new QueueCommand(code, "Fila " + code, null, priority, creditorId, null, null, null, null, null, null, null,
                "OLDEST_OVERDUE", strategy, capacity, teamIds);
    }

    RecordInteractionCommand interaction(String tabulation, InteractionUseCases.PromiseInput promise,
                                         InteractionUseCases.CallbackInput callback, String note) {
        return new RecordInteractionCommand("PHONE", "OUTBOUND", null, tabulation, note, null, null, null, promise, callback,
                null, null, null);
    }

    RecordResult record(UUID userId, UUID caseId, RecordInteractionCommand command) {
        return asOperator(userId, () -> interactions.record(caseId, command));
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

    <T> T asOperator(UUID userId, Supplier<T> action) {
        return as(userId, DefaultRoles.OPERADOR, userId.equals(operatorZ) ? CreditorScope.of(Set.of(creditorB))
                : CreditorScope.ALL, action);
    }

    <T> T asSupervisor(Supplier<T> action) {
        return as(supervisor, DefaultRoles.SUPERVISOR, CreditorScope.ALL, action);
    }

    <T> T asManager(Supplier<T> action) {
        return as(Ids.newId(), DefaultRoles.GESTOR, CreditorScope.ALL, action);
    }

    void publishAndAwait(IntegrationEvent event, String consumer) {
        tenantExecution.inTenant(tenant, "test.publish", () -> publisher.publishEvent(event));
        awaitConsumed(event.eventId(), consumer);
    }

    void awaitConsumed(UUID eventId, String consumer) {
        awaitTrue("consumo por " + consumer, () -> {
            Long consumed = jdbcTemplate.queryForObject(
                    "select count(*) from shared.consumed_event where consumer = ? and event_id = ?", Long.class,
                    consumer, eventId);
            return consumed != null && consumed == 1;
        });
    }

    /** Espera o consumo de todos os eventos de integração do tenant já publicados por {@code consumer}. */
    void awaitIdle(String consumerPrefix) {
        awaitTrue("eventos pendentes de " + consumerPrefix, () -> {
            Long pending = jdbcTemplate.queryForObject("""
                    select count(*) from event_publication
                     where completion_date is null and listener_id like ? and serialized_event like ?
                    """, Long.class, "%" + consumerPrefix + "%", "%" + tenant + "%");
            return pending != null && pending == 0;
        }, () -> jdbcTemplate.queryForList("""
                select listener_id, event_type, publication_date, status, completion_attempts
                  from event_publication
                 where completion_date is null and listener_id like ? and serialized_event like ?
                """, "%" + consumerPrefix + "%", "%" + tenant + "%").toString());
    }

    long count(String sql, Object... args) {
        return system(() -> {
            Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
            return value == null ? 0L : value;
        });
    }

    static void awaitTrue(String what, BooleanSupplier condition) {
        awaitTrue(what, condition, () -> "");
    }

    static void awaitTrue(String what, BooleanSupplier condition, java.util.function.Supplier<String> diagnostics) {
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
        StringBuilder threads = new StringBuilder();
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            if (thread.getName().startsWith("event-") || thread.getName().startsWith("job-")) {
                threads.append("\n").append(thread.getName()).append(' ').append(thread.getState());
                for (int i = 0; i < Math.min(stack.length, 12); i++) {
                    threads.append("\n    at ").append(stack[i]);
                }
            }
        });
        fail("tempo esgotado aguardando " + what + " " + diagnostics.get() + threads);
    }
}
