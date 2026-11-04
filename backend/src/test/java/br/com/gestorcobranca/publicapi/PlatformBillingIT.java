package br.com.gestorcobranca.publicapi;

import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.publicapi.application.port.in.IntegrationTokenUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken.IssuedToken;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.BreakdownView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.CreatePolicyCommand;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.OverviewView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PolicyTermsCommand;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PolicyView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PositionView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.StatementView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.TenantSummary;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.TierView;
import br.com.gestorcobranca.publicapi.application.port.in.TenantPlatformBillingUseCases;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faturamento da plataforma às assessorias (segunda camada): agregação do consumo de todos os tokens (inclusive
 * revogados) de todos os credores da assessoria, fechamento automático idempotente (também com execuções simultâneas),
 * revisão com motivo, política versionada pelos casos de uso, posição e consolidado, isolamento entre assessorias
 * (RLS), permissões exclusivas da plataforma e a visão somente leitura da própria assessoria.
 */
class PlatformBillingIT extends PublicApiTestSupport {

    private static final ZoneId ZONE = TimeConfig.BUSINESS_ZONE;
    private static final long MB = 1_048_576L;

    @Autowired
    PlatformBillingUseCases platformBilling;
    @Autowired
    TenantPlatformBillingUseCases tenantBilling;
    @Autowired
    IntegrationTokenUseCases tokenUseCases;
    @Autowired
    ApplicationContext context;

    UUID platformTenant;
    UUID platformUser;
    UUID otherTenant;
    UUID otherCreditor;

    @BeforeEach
    void platformAndSecondTenant() {
        platformTenant = tenantExecution.callInTenant(Ids.newId(), "test.setup",
                () -> tenantAdministration.ensurePlatformTenant("11.444.777/0001-61").tenant().id());
        platformUser = Ids.newId();
        String slug = "pb-" + UUID.randomUUID().toString().substring(0, 8);
        otherTenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, "Outra Assessoria Ltda", "Outra Assessoria",
                        "11.444.777/0001-61")).id());
        otherCreditor = in(otherTenant, () -> creditors.create(
                new br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand(
                        "Banco C S.A.", "Banco C", "11.222.333/0001-81", null, null)).id());
    }

    // ------------------------------------------------------------------ apoio

    <T> T in(UUID tenantId, Supplier<T> action) {
        return tenantExecution.callInTenant(tenantId, "test", action);
    }

    <T> T asPlatform(Supplier<T> action) {
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, platformUser, platformTenant, Ids.newId(),
                "plataforma", EnumSet.of(Permission.TENANT_MANAGE, Permission.PLATFORM_BILLING_VIEW,
                Permission.PLATFORM_BILLING_MANAGE), CreditorScope.ALL), action);
    }

    <T> T asTenantAdmin(UUID tenantId, CreditorScope scope, Supplier<T> action) {
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, Ids.newId(), tenantId, Ids.newId(), "admin",
                EnumSet.of(Permission.TENANT_BILLING_VIEW, Permission.REPORT_EXPORT), scope), action);
    }

    /** Política registrada diretamente com instante passado, como se cadastrada antes dos ciclos. */
    void insertPolicy(UUID tenantId, LocalDate start, int closingDay, Instant registeredAt, String fee) {
        UUID policy = UUID.randomUUID();
        in(tenantId, () -> {
            jdbc.update("""
                    insert into publicapi.platform_billing_policy (id, tenant_id, version, created_at, updated_at,
                        start_date) values (?, ?, 0, now(), now(), ?)""", policy, tenantId, start);
            return jdbc.update("""
                    insert into publicapi.platform_billing_policy_version (id, tenant_id, version, created_at,
                        updated_at, policy_id, policy_version, closing_day, monthly_fee, included_requests,
                        price_per_request, request_tiers, included_mb_in, price_per_mb_in, included_mb_out,
                        price_per_mb_out, included_records, price_per_record, registered_at)
                    values (?, ?, 0, now(), now(), ?, 1, ?, ?, 100, 0.10, '[]'::jsonb, 0, 1.00, 0, 0.50, 0, 0.02, ?)""",
                    UUID.randomUUID(), tenantId, policy, closingDay, new BigDecimal(fee), Timestamp.from(registeredAt));
        });
    }

    void usage(UUID tenantId, UUID creditorId, UUID tokenId, Instant hour, long requests, long clientErrors,
               long serverErrors, long billableBytesIn, long records) {
        in(tenantId, () -> jdbc.update("""
                insert into publicapi.usage_hourly (tenant_id, creditor_id, token_id, hour_start, method, route, requests,
                    throttled, client_errors, server_errors, bytes_in, bytes_out, billable_bytes_in, billable_bytes_out,
                    records, latency_ms_total, updated_at)
                values (?, ?, ?, date_trunc('hour', ?::timestamptz), 'POST', '/public-api/v1/imports', ?, 0, ?, ?, ?, 0,
                    ?, 0, ?, 100, now())""",
                tenantId, creditorId, tokenId, Timestamp.from(hour), requests, clientErrors, serverErrors,
                billableBytesIn + 999, billableBytesIn, records));
    }

    IssuedToken issueIn(UUID tenantId, UUID creditorId) {
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, Ids.newId(), tenantId, Ids.newId(), "gestor",
                EnumSet.complementOf(EnumSet.copyOf(Permission.PLATFORM_ONLY)), CreditorScope.ALL),
                () -> issuer.issue(new br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken
                        .IssueTokenCommand(creditorId, "ERP " + creditorId.toString().substring(0, 4), null,
                        Set.of("portfolios:read"), null, List.of(), null)));
    }

    int closeAllByJob() throws Exception {
        Object job = context.getBean(Class.forName(
                "br.com.gestorcobranca.publicapi.application.service.platform.PlatformStatementClosingJob"));
        Method closeAll = job.getClass().getDeclaredMethod("closeAll");
        closeAll.setAccessible(true);
        return (Integer) closeAll.invoke(job);
    }

    List<StatementView> platformStatements(UUID tenantId) {
        return asPlatform(() -> platformBilling.statements(tenantId, null, PageQuery.of(0, 100))).content().stream()
                .sorted(Comparator.comparing(StatementView::periodStart).thenComparing(StatementView::revision)).toList();
    }

    static PolicyTermsCommand tieredTerms(String fee) {
        return new PolicyTermsCommand(31, new BigDecimal(fee), 0, null, List.of(new TierView(500L, new BigDecimal("0.01")),
                new TierView(null, new BigDecimal("0.005"))), 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------ cenários

    @Test
    void statementAggregatesEveryTokenOfEveryCreditorIncludingRevokedAndClosesOnce() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate start = today.minusMonths(3).withDayOfMonth(1);
        LocalDate m2 = today.minusMonths(2).withDayOfMonth(1);
        insertPolicy(tenant, start, 31, start.minusDays(1).atStartOfDay(ZONE).toInstant(), "200.00");

        IssuedToken a1 = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        IssuedToken b1 = issue(creditorB, Set.of("portfolios:read"), null, List.of());
        IssuedToken a2 = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        asManager(() -> tokenUseCases.revoke(a2.details().id(), "trocado pelo sistema novo"));
        Instant hour = m2.plusDays(5).atTime(12, 0).atZone(ZONE).toInstant();
        usage(tenant, creditorA, a1.details().id(), hour, 100, 10, 0, MB + 1, 5);   // 90 cobráveis, 2 MB
        usage(tenant, creditorB, b1.details().id(), hour, 50, 0, 0, 1, 0);         // 50 cobráveis, 1 MB
        usage(tenant, creditorA, a2.details().id(), hour, 30, 0, 5, 0, 0);         // revogado: 25 cobráveis
        // outra assessoria no mesmo período: fica fora (RLS)
        IssuedToken other = issueIn(otherTenant, otherCreditor);
        usage(otherTenant, otherCreditor, other.details().id(), hour, 400, 0, 0, 10 * MB, 0);

        int closedNow = closeAllByJob();
        assertThat(closedNow).isGreaterThanOrEqualTo(3);
        List<StatementView> all = platformStatements(tenant);
        assertThat(all).extracting(StatementView::periodStart)
                .containsExactly(start, start.plusMonths(1), start.plusMonths(2));
        assertThat(all.getFirst().cycleKind()).isEqualTo("POLICY_START");
        assertThat(all.getFirst().totalAmount()).isEqualByComparingTo("200.00");
        assertThat(all.get(2).totalAmount()).isEqualByComparingTo("200.00");

        StatementView s = all.get(1);
        assertThat(s.periodEnd()).isEqualTo(m2.withDayOfMonth(m2.lengthOfMonth()));
        assertThat(s.requests()).isEqualTo(180);
        assertThat(s.billableRequests()).isEqualTo(165);
        assertThat(s.billableMbIn()).isEqualTo(3);
        assertThat(s.records()).isEqualTo(5);
        assertThat(s.creditors()).isEqualTo(2);
        assertThat(s.tokens()).isEqualTo(3);
        // 200,00 + (165 − 100) × 0,10 + 3 MB × 1,00 + 5 × 0,02
        assertThat(s.totalAmount()).isEqualByComparingTo("209.60");
        assertThat(s.closedBy()).isNull();
        assertThat(s.breakdown()).filteredOn(b -> b.kind().equals("CREDITOR")).extracting(BreakdownView::label)
                .containsExactlyInAnyOrder("Banco A", "Loja B");
        assertThat(s.breakdown()).filteredOn(b -> b.kind().equals("TOKEN")).extracting(BreakdownView::key)
                .containsExactlyInAnyOrder(a1.details().id().toString(), b1.details().id().toString(),
                        a2.details().id().toString());
        assertThat(s.breakdown()).filteredOn(b -> b.kind().equals("CREDITOR") && b.label().equals("Banco A"))
                .singleElement().satisfies(b -> assertThat(b.totals()).containsEntry("billableRequests", 115L)
                        .containsEntry("billableMbIn", 2L));

        // segunda execução (e fechamento manual) não duplica: o ciclo em andamento ainda não terminou
        closeAllByJob();
        assertThat(platformStatements(tenant)).hasSize(3);
        assertThatThrownBy(() -> asPlatform(() -> platformBilling.close(tenant, null, null)))
                .isInstanceOf(BusinessRuleException.class);
        // o mesmo ciclo só de novo como revisão com motivo; mesmo consumo, mesma marca e mesmo valor
        assertThatThrownBy(() -> asPlatform(() -> platformBilling.close(tenant, m2, null)))
                .isInstanceOf(ConflictException.class);
        StatementView revision = asPlatform(() -> platformBilling.close(tenant, m2, "conferência com o financeiro"));
        assertThat(revision.revision()).isEqualTo(2);
        assertThat(revision.usageHash()).isEqualTo(s.usageHash());
        assertThat(revision.totalAmount()).isEqualByComparingTo(s.totalAmount());
        assertThat(revision.closedBy()).isEqualTo(platformUser);
        assertThat(platformStatements(tenant)).hasSize(4);

        // extrato imutável no banco
        assertThatThrownBy(() -> in(tenant, () -> jdbc.update(
                "update publicapi.platform_statement set total_amount = 0 where id = ?", s.id())))
                .rootCause().hasMessageContaining("platform_statement");

        // trilhas: assessoria (fechamento automático e revisão) e plataforma (revisão pedida por ela)
        assertThat(in(tenant, () -> jdbc.queryForList("select action from shared.audit_event where entity_id = ?",
                String.class, s.id()))).containsExactly("platform_statement.closed");
        assertThat(in(platformTenant, () -> jdbc.queryForObject("""
                select count(*) from shared.audit_event where action = 'platform_statement.reclosed'
                   and entity_id = ?""", Long.class, tenant))).isEqualTo(1L);
        assertThat(in(tenant, () -> jdbc.queryForObject("""
                select count(*) from shared.business_event where event_type = 'platform_billing.statement_closed'""",
                Long.class))).isEqualTo(4L);

        // CSV com as linhas e o detalhamento, registrado como exportação no log da plataforma
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        asPlatform(() -> {
            platformBilling.exportStatement(tenant, s.id(), out);
            return null;
        });
        String csvText = out.toString(StandardCharsets.UTF_8);
        assertThat(csvText).contains("linha;REQUESTS").contains("detalhe_creditor").contains("detalhe_token")
                .contains(s.usageHash());
        assertThat(in(platformTenant, () -> jdbc.queryForObject("""
                select count(*) from shared.access_log where event_type = 'DATA_EXPORTED'
                   and details ->> 'targetTenantId' = ?""", Long.class, tenant.toString()))).isEqualTo(1L);
    }

    @Test
    void simultaneousClosingsOfTheSameTenantProduceEachStatementOnce() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate start = today.minusMonths(2).withDayOfMonth(1);
        insertPolicy(tenant, start, 31, start.minusDays(1).atStartOfDay(ZONE).toInstant(), "50.00");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(this::closeAllByJob);
            Future<Integer> second = pool.submit(() -> asPlatformClose(start));
            first.get();
            second.get();
        } finally {
            pool.shutdownNow();
        }
        List<StatementView> all = platformStatements(tenant);
        assertThat(all).extracting(StatementView::periodStart).containsExactly(start, start.plusMonths(1));
        assertThat(all).allSatisfy(s -> assertThat(s.revision()).isEqualTo(1));
    }

    /** Fechamento manual concorrente: fecha o ciclo ou encontra-o já fechado (conflito sem motivo). */
    private int asPlatformClose(LocalDate start) {
        try {
            asPlatform(() -> platformBilling.close(tenant, null, null));
            return 1;
        } catch (BusinessRuleException | ConflictException e) {
            return 0;
        }
    }

    @Test
    void policyIsVersionedByThePlatformAndThePositionPricesTheCurrentCycleWithVolumeTiers() {
        LocalDate today = LocalDate.now(ZONE);
        IssuedToken token = issueIn(otherTenant, otherCreditor);
        usage(otherTenant, otherCreditor, token.details().id(), today.atStartOfDay(ZONE).toInstant(), 1_000, 0, 0, 0, 0);

        PolicyView none = asPlatform(() -> platformBilling.policy(otherTenant));
        assertThat(none.policyId()).isNull();
        assertThat(asPlatform(() -> platformBilling.position(otherTenant))).satisfies(p -> {
            assertThat(p.hasPolicy()).isFalse();
            assertThat(p.cycleKind()).isEqualTo("NO_POLICY");
            assertThat(p.billableRequests()).isEqualTo(1_000);
            assertThat(p.amountSoFar()).isEqualByComparingTo("0.00");
        });

        PolicyView v1 = asPlatform(() -> platformBilling.createPolicy(otherTenant,
                new CreatePolicyCommand(today.withDayOfMonth(1), null, tieredTerms("0.00"), "contrato assinado")));
        assertThat(v1.versions()).singleElement().satisfies(v -> {
            assertThat(v.version()).isEqualTo(1);
            assertThat(v.appliesToCurrentCycle()).isTrue();
            assertThat(v.requestTiers()).hasSize(2);
            assertThat(v.registeredBy()).isEqualTo(platformUser);
        });
        assertThatThrownBy(() -> asPlatform(() -> platformBilling.createPolicy(otherTenant,
                new CreatePolicyCommand(today, null, tieredTerms("0.00"), null)))).isInstanceOf(ConflictException.class);

        // primeiro cadastro vale no ciclo em andamento: 500 × 0,01 + 500 × 0,005
        PositionView position = asPlatform(() -> platformBilling.position(otherTenant));
        assertThat(position.hasPolicy()).isTrue();
        assertThat(position.policyVersion()).isEqualTo(1);
        assertThat(position.amountSoFar()).isEqualByComparingTo("7.50");
        assertThat(position.lines().get(1).tiers()).hasSize(2);
        assertThat(position.daily()).isNotEmpty();
        assertThat(position.projection().amount()).isGreaterThanOrEqualTo(position.amountSoFar());

        // nova versão: vale a partir do próximo ciclo; o ciclo atual continua na v1
        PolicyView v2 = asPlatform(() -> platformBilling.addVersion(otherTenant, tieredTerms("99.00"), "reajuste"));
        assertThat(v2.versions()).extracting(PlatformBillingUseCases.PolicyVersionView::version).containsExactly(2, 1);
        assertThat(v2.versions()).extracting(PlatformBillingUseCases.PolicyVersionView::appliesToCurrentCycle)
                .containsExactly(false, true);
        assertThat(asPlatform(() -> platformBilling.position(otherTenant)).amountSoFar()).isEqualByComparingTo("7.50");

        // vigência: motivo obrigatório; fim no passado recusado
        assertThatThrownBy(() -> asPlatform(() -> platformBilling.changeTerm(otherTenant, today.withDayOfMonth(1),
                today.minusDays(400), "encerramento"))).isInstanceOf(BusinessRuleException.class);
        PolicyView ended = asPlatform(() -> platformBilling.changeTerm(otherTenant, today.withDayOfMonth(1),
                today.plusMonths(6), "contrato de 6 meses"));
        assertThat(ended.endDate()).isEqualTo(today.plusMonths(6));

        // auditoria nas duas trilhas, evento de negócio e acesso da plataforma registrado
        assertThat(in(otherTenant, () -> jdbc.queryForList("""
                select action from shared.audit_event where entity_type = 'platform_billing_policy' order by seq""",
                String.class))).containsExactly("platform_billing.policy_created", "platform_billing.policy_version_added",
                "platform_billing.policy_term_changed");
        assertThat(in(platformTenant, () -> jdbc.queryForObject("""
                select count(*) from shared.audit_event where entity_type = 'tenant' and entity_id = ?
                   and action like 'platform_billing.%'""", Long.class, otherTenant))).isEqualTo(3L);
        assertThat(in(otherTenant, () -> jdbc.queryForObject("""
                select count(*) from shared.business_event where event_type = 'platform_billing.policy_defined'""",
                Long.class))).isEqualTo(2L);
        assertThat(in(platformTenant, () -> jdbc.queryForObject("""
                select count(*) from shared.access_log where event_type = 'PLATFORM_TENANT_DATA_VIEWED'
                   and details ->> 'targetTenantId' = ?""", Long.class, otherTenant.toString()))).isGreaterThanOrEqualTo(3L);

        // o tenant da plataforma não é cobrado
        assertThatThrownBy(() -> asPlatform(() -> platformBilling.policy(platformTenant)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void overviewRanksTenantsByConsumptionAndFlagsTenantsWithoutPolicy() {
        LocalDate today = LocalDate.now(ZONE);
        insertPolicy(tenant, today.withDayOfMonth(1), 31, today.withDayOfMonth(1).minusDays(1).atStartOfDay(ZONE)
                .toInstant(), "200.00");
        IssuedToken a = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        usage(tenant, creditorA, a.details().id(), today.atStartOfDay(ZONE).toInstant(), 10, 0, 0, 0, 0);
        IssuedToken other = issueIn(otherTenant, otherCreditor);
        usage(otherTenant, otherCreditor, other.details().id(), today.atStartOfDay(ZONE).toInstant(), 5_000, 0, 0, 0, 0);

        OverviewView overview = asPlatform(platformBilling::overview);
        Map<UUID, TenantSummary> byId = overview.tenants().stream()
                .collect(java.util.stream.Collectors.toMap(TenantSummary::tenantId, t -> t));
        assertThat(byId).doesNotContainKey(platformTenant);
        TenantSummary withPolicy = byId.get(tenant);
        TenantSummary withoutPolicy = byId.get(otherTenant);
        assertThat(withPolicy.hasPolicy()).isTrue();
        assertThat(withPolicy.amountSoFar()).isEqualByComparingTo("200.00");
        assertThat(withoutPolicy.hasPolicy()).isFalse();
        assertThat(withoutPolicy.amountSoFar()).isEqualByComparingTo("0.00");
        assertThat(withoutPolicy.billableRequests()).isEqualTo(5_000);
        assertThat(withoutPolicy.rank()).isLessThan(withPolicy.rank());
        assertThat(overview.totals().withoutPolicy()).isGreaterThanOrEqualTo(1);
        assertThat(overview.totals().amountSoFar()).isGreaterThanOrEqualTo(new BigDecimal("200.00"));
    }

    @Test
    void onlyThePlatformManagesAndTheTenantReadsOnlyItsOwnBilling() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate start = today.minusMonths(1).withDayOfMonth(1);
        insertPolicy(tenant, start, 31, start.minusDays(1).atStartOfDay(ZONE).toInstant(), "10.00");
        closeAllByJob();
        UUID statementId = platformStatements(tenant).getFirst().id();

        // a assessoria lê os próprios extratos, a posição e a política (somente leitura)
        assertThat(asTenantAdmin(tenant, CreditorScope.ALL, () -> tenantBilling.statements(null, PageQuery.of(0, 10)))
                .content()).extracting(StatementView::id).containsExactly(statementId);
        assertThat(asTenantAdmin(tenant, CreditorScope.ALL, tenantBilling::position).hasPolicy()).isTrue();
        assertThat(asTenantAdmin(tenant, CreditorScope.ALL, tenantBilling::policy).versions()).hasSize(1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        asTenantAdmin(tenant, CreditorScope.ALL, () -> {
            tenantBilling.exportStatement(statementId, out);
            return null;
        });
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("linha;MONTHLY_FEE");
        // com escopo parcial de credores, não (o extrato soma todos os credores)
        assertThatThrownBy(() -> asTenantAdmin(tenant, CreditorScope.of(Set.of(creditorA)), tenantBilling::position))
                .isInstanceOf(ScopeAccessDeniedException.class);
        // outra assessoria não alcança o extrato (RLS → 404) e não vê nada dele
        assertThatThrownBy(() -> asTenantAdmin(otherTenant, CreditorScope.ALL, () -> tenantBilling.statement(statementId)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(asTenantAdmin(otherTenant, CreditorScope.ALL, () -> tenantBilling.statements(null, PageQuery.of(0, 10)))
                .content()).isEmpty();
        // sem a permissão da assessoria, nada
        assertThatThrownBy(() -> asUser(UUID.randomUUID(), EnumSet.of(Permission.PUBLIC_API_USAGE_VIEW),
                CreditorScope.ALL, tenantBilling::position)).isInstanceOf(ScopeAccessDeniedException.class);

        // a assessoria não usa os casos de uso da plataforma: sem a permissão, ou com ela fora do tenant da plataforma
        assertThatThrownBy(() -> asTenantAdmin(tenant, CreditorScope.ALL, platformBilling::overview))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> tenantExecution.callAs(new Actor(Actor.ActorType.USER, Ids.newId(), tenant,
                Ids.newId(), "forjado", EnumSet.of(Permission.PLATFORM_BILLING_MANAGE, Permission.PLATFORM_BILLING_VIEW),
                CreditorScope.ALL), () -> platformBilling.close(tenant, start, "tentativa")))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(in(tenant, () -> jdbc.queryForObject("""
                select count(*) from shared.access_log where event_type = 'ACCESS_DENIED'
                   and details ->> 'reason' = 'platform_tenant_required'""", Long.class))).isEqualTo(1L);
        // só a visão: quem consulta a plataforma não fecha nem altera
        assertThatThrownBy(() -> tenantExecution.callAs(new Actor(Actor.ActorType.USER, Ids.newId(), platformTenant,
                Ids.newId(), "leitura", EnumSet.of(Permission.PLATFORM_BILLING_VIEW), CreditorScope.ALL),
                () -> platformBilling.addVersion(tenant, tieredTerms("1.00"), null)))
                .isInstanceOf(ScopeAccessDeniedException.class);
    }
}
