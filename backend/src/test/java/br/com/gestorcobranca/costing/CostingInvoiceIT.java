package br.com.gestorcobranca.costing;

import br.com.gestorcobranca.costing.application.port.in.ChannelInvoiceUseCases;
import br.com.gestorcobranca.costing.application.port.in.ChannelInvoiceUseCases.InvoiceView;
import br.com.gestorcobranca.costing.application.port.in.ChannelInvoiceUseCases.ItemEstimateView;
import br.com.gestorcobranca.costing.application.port.in.ChannelInvoiceUseCases.SaveInvoiceCommand;
import br.com.gestorcobranca.costing.application.port.in.CostRefreshUseCases;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.CreateTariffCommand;
import br.com.gestorcobranca.costing.application.port.in.MarginExportUseCases;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.CreditorMargin;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.MarginFilter;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.MarginReport;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.MarginWarning;
import br.com.gestorcobranca.costing.domain.model.ChannelItem;
import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.MarginBasis;
import br.com.gestorcobranca.costing.domain.model.ReconciliationStatus;
import br.com.gestorcobranca.costing.domain.model.TariffChannel;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.CreateUserCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Conciliação do custo de canal com a fatura real (desenho margem-por-carteira §3.5 e §13): estimativa por item na
 * apuração, fatura → ajuste rateado pela estimativa (fato à parte), margem nas duas bases, escopo restrito (só o ajuste
 * dos próprios credores, nunca a fatura nem a linha sem credor), reapuração que preserva o ajuste, auditoria, 409 com
 * outra apuração em andamento e o contrato HTTP.
 */
class CostingInvoiceIT extends IntegrationTest {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    static final LocalDate FROM = OCTOBER.atDay(1);
    static final LocalDate TO = OCTOBER.atEndOfMonth();

    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantProvisioning provisioning;
    @Autowired
    RoleManagementUseCases roles;
    @Autowired
    UserManagementUseCases userManagement;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CostSettingsUseCases settings;
    @Autowired
    CostRefreshUseCases refresh;
    @Autowired
    ChannelInvoiceUseCases invoices;
    @Autowired
    MarginQueries margins;
    @Autowired
    MarginExportUseCases exports;
    @Autowired
    WebApplicationContext webApplicationContext;

    UUID tenant;
    UUID manager;
    UUID finance;
    UUID creditorA;
    UUID creditorB;
    UUID portfolioA;
    UUID portfolioB;

    @Test
    void faturaAjustaOCustoDeCanalNaBaseConciliadaSemAlterarAEstimativa() throws Exception {
        seed();
        asFinance(() -> refresh.refresh(FROM, TO));

        // estimativa por item na assessoria: telefonia 3 min × R$ 1 (A 2, B 1); WhatsApp 1 conversa em A; SMS 1 em B
        Map<ChannelItem, ItemEstimateView> estimates = asFinance(() -> invoices.estimates(OCTOBER, OCTOBER)).stream()
                .collect(Collectors.toMap(ItemEstimateView::item, e -> e));
        assertThat(estimates).hasSize(ChannelItem.values().length);
        assertThat(estimates.get(ChannelItem.TELEPHONY).estimated()).isEqualByComparingTo("3.00");
        assertThat(estimates.get(ChannelItem.TELEPHONY).quantity()).isEqualByComparingTo("3");
        assertThat(estimates.get(ChannelItem.TELEPHONY).unit()).isEqualTo("MINUTES");
        assertThat(estimates.get(ChannelItem.WHATSAPP).estimated()).isEqualByComparingTo("0.40");
        assertThat(estimates.get(ChannelItem.SMS).estimated()).isEqualByComparingTo("0.10");
        assertThat(estimates.get(ChannelItem.EMAIL).estimated()).isZero();

        // fatura da telefonia R$ 6 (estimativa R$ 3): ajuste de R$ 3 rateado 2 para A e 1 para B
        InvoiceView phone = asFinance(() -> invoices.save(new SaveInvoiceCommand(OCTOBER, ChannelItem.TELEPHONY,
                new BigDecimal("6.00"), "Operadora fictícia", "fatura de outubro")));
        assertThat(phone.estimated()).isEqualByComparingTo("3.00");
        assertThat(phone.adjustment()).isEqualByComparingTo("3.00");
        // fatura de e-mail sem nenhum uso no mês: o valor fica sem credor, com aviso (só na base conciliada)
        asFinance(() -> invoices.save(new SaveInvoiceCommand(OCTOBER, ChannelItem.EMAIL, new BigDecimal("5.00"), null,
                null)));

        MarginReport estimated = asManager(null, () -> margins.margin(new MarginFilter(FROM, TO, null, null)));
        MarginReport reconciled = asManager(null, () -> margins.margin(new MarginFilter(FROM, TO, null, null,
                MarginBasis.RECONCILED)));
        assertThat(estimated.basis()).isEqualTo("ESTIMATED");
        assertThat(reconciled.basis()).isEqualTo("RECONCILED");
        // a estimativa não muda; a base conciliada soma o ajuste
        assertThat(cost(creditor(estimated, creditorA), CostCategory.TELEPHONY)).isEqualByComparingTo("2.00");
        assertThat(cost(creditor(estimated, creditorB), CostCategory.TELEPHONY)).isEqualByComparingTo("1.00");
        assertThat(cost(creditor(reconciled, creditorA), CostCategory.TELEPHONY)).isEqualByComparingTo("4.00");
        assertThat(cost(creditor(reconciled, creditorB), CostCategory.TELEPHONY)).isEqualByComparingTo("2.00");
        assertThat(reconciled.totals().costs().get(CostCategory.TELEPHONY)).isEqualByComparingTo("6.00");
        assertThat(reconciled.unallocated().costs().get(CostCategory.MESSAGING)).isEqualByComparingTo("5.00");
        assertThat(reconciled.totals().totalCost().subtract(estimated.totals().totalCost()))
                .isEqualByComparingTo("8.00");
        assertThat(warnings(reconciled)).containsEntry("INVOICE_WITHOUT_USAGE", BigDecimal.ONE);
        // a tendência segue a base: outubro da tendência conciliada = margem líquida conciliada do painel (decisão 1327)
        MarginQueries.MarginTrend trend = asManager(null, () -> margins.trend(new MarginFilter(FROM, TO, null, null,
                MarginBasis.RECONCILED)));
        MarginQueries.MarginTrend estimatedTrend = asManager(null, () -> margins.trend(new MarginFilter(FROM, TO, null,
                null)));
        for (var pair : List.of(Map.entry(trend, reconciled), Map.entry(estimatedTrend, estimated))) {
            var october = pair.getKey().creditors().stream().filter(c -> c.creditorId().equals(creditorA)).findFirst()
                    .orElseThrow().points().getLast();
            assertThat(october.netMargin()).isEqualByComparingTo(creditor(pair.getValue(), creditorA).line().netMargin());
        }
        assertThat(warnings(estimated)).doesNotContainKey("INVOICE_WITHOUT_USAGE");
        // situação e ajuste por categoria, nas duas bases
        for (MarginReport report : new MarginReport[] {estimated, reconciled}) {
            assertThat(report.reconciliation().status()).isEqualTo(ReconciliationStatus.PARTIAL);
            assertThat(report.reconciliation().months()).singleElement().satisfies(m -> {
                assertThat(m.month()).isEqualTo("2026-10");
                assertThat(m.invoiced()).containsExactly(ChannelItem.TELEPHONY, ChannelItem.EMAIL);
                assertThat(m.pending()).containsExactly(ChannelItem.WHATSAPP, ChannelItem.SMS);
            });
            assertThat(report.reconciliation().adjustments()).containsEntry(CostCategory.TELEPHONY,
                    new BigDecimal("3.00")).containsEntry(CostCategory.MESSAGING, new BigDecimal("5.00"))
                    .containsEntry(CostCategory.AI, new BigDecimal("0.00"));
            assertThat(report.reconciliation().invoices()).hasSize(2);
        }

        // escopo restrito ao credor B: só o ajuste de B, sem fatura nem linha sem credor
        MarginReport onlyB = asManager(Set.of(creditorB), () -> margins.margin(new MarginFilter(FROM, TO, null, null,
                MarginBasis.RECONCILED)));
        assertThat(onlyB.creditors()).extracting(CreditorMargin::creditorId).containsExactly(creditorB);
        assertThat(onlyB.unallocated()).isNull();
        assertThat(onlyB.reconciliation().invoices()).isNull();
        // situação para todos; os canais com uso e com fatura são da assessoria inteira (não revelam outro credor)
        assertThat(onlyB.reconciliation().status()).isEqualTo(ReconciliationStatus.PARTIAL);
        assertThat(onlyB.reconciliation().months()).singleElement().satisfies(m -> {
            assertThat(m.invoiced()).isEmpty();
            assertThat(m.pending()).isEmpty();
        });
        assertThat(onlyB.reconciliation().adjustments()).containsEntry(CostCategory.TELEPHONY, new BigDecimal("1.00"))
                .containsEntry(CostCategory.MESSAGING, new BigDecimal("0.00"));
        assertThat(warnings(onlyB)).doesNotContainKey("INVOICE_WITHOUT_USAGE");
        assertThatThrownBy(() -> asManager(Set.of(creditorB), () -> invoices.invoices(OCTOBER, OCTOBER)))
                .isInstanceOf(ScopeAccessDeniedException.class);
        // com escopo total e filtro de credor, também sem as faturas
        assertThat(asManager(null, () -> margins.margin(new MarginFilter(FROM, TO, Set.of(creditorA), null,
                MarginBasis.RECONCILED))).reconciliation().invoices()).isNull();

        // a reapuração refaz a estimativa e o ajuste do mês: o resultado não muda nem duplica
        asFinance(() -> refresh.refresh(FROM, TO));
        MarginReport again = asManager(null, () -> margins.margin(new MarginFilter(FROM, TO, null, null,
                MarginBasis.RECONCILED)));
        assertThat(again.totals().totalCost()).isEqualByComparingTo(reconciled.totals().totalCost());
        assertThat(system(() -> jdbc.queryForObject("""
                select sum(amount) from costing.cost_daily where basis = 'INVOICE_ADJUSTMENT'""", BigDecimal.class)))
                .isEqualByComparingTo("8.00");

        // substituir a fatura (auditado) refaz o ajuste na hora; excluir (com motivo) remove o ajuste
        asFinance(() -> invoices.save(new SaveInvoiceCommand(OCTOBER, ChannelItem.TELEPHONY, new BigDecimal("3.00"),
                "Operadora fictícia", "fatura corrigida")));
        assertThat(cost(creditor(asManager(null, () -> margins.margin(new MarginFilter(FROM, TO, null, null,
                MarginBasis.RECONCILED))), creditorA), CostCategory.TELEPHONY)).isEqualByComparingTo("2.00");
        UUID emailId = asFinance(() -> invoices.invoices(OCTOBER, OCTOBER)).stream()
                .filter(i -> i.item() == ChannelItem.EMAIL).findFirst().orElseThrow().id();
        asFinance(() -> {
            invoices.delete(emailId, "lançada no item errado");
            return null;
        });
        assertThat(asManager(null, () -> margins.margin(new MarginFilter(FROM, TO, null, null,
                MarginBasis.RECONCILED))).unallocated().costs().get(CostCategory.MESSAGING)).isZero();
        assertThat(system(() -> jdbc.queryForList("""
                select action from shared.audit_event where action like 'costing.invoice_%' order by occurred_at""",
                String.class))).containsExactly("costing.invoice_created", "costing.invoice_created",
                "costing.invoice_changed", "costing.invoice_deleted");
        assertThat(system(() -> jdbc.queryForObject("""
                select reason from shared.audit_event where action = 'costing.invoice_deleted'""", String.class)))
                .isEqualTo("lançada no item errado");

        // exportação: a base no Resumo e as faturas em Premissas só para escopo total
        String csv = new String(asManager(null, () -> exports.export(new MarginFilter(FROM, TO, null, null,
                MarginBasis.RECONCILED), "csv")).content().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(csv).contains("base;RECONCILED;Conciliada com a fatura").contains("premissas_faturas;2026-10/TELEPHONY")
                .contains("ajuste_fatura;TELEPHONY");
        String restrictedCsv = new String(asManager(Set.of(creditorB), () -> exports.export(new MarginFilter(FROM, TO,
                null, null, MarginBasis.RECONCILED), "csv")).content().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(restrictedCsv).doesNotContain("premissas_faturas");
        // escopo total com filtro de credor: a planilha "do credor A" não leva as faturas da assessoria
        String filteredCsv = new String(asManager(null, () -> exports.export(new MarginFilter(FROM, TO,
                Set.of(creditorA), null, MarginBasis.RECONCILED), "csv")).content().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(filteredCsv).doesNotContain("premissas_faturas");

        // os fatos do ajuste não têm pessoa e respeitam a assessoria
        assertThat(jdbc.queryForObject("select count(*) from costing.channel_invoice", Long.class)).isZero();
    }

    @Test
    void escritaExigeCostManageTodosOsCredoresENaoEsperaAApuracaoEmAndamento() throws Exception {
        seed();
        asFinance(() -> refresh.refresh(FROM, TO));
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        // gestor lê, mas não informa fatura; escopo restrito não informa nem com COST_MANAGE
        assertThat(asManager(null, () -> invoices.invoices(OCTOBER, OCTOBER))).isEmpty();
        assertThatThrownBy(() -> asManager(null, () -> invoices.save(new SaveInvoiceCommand(OCTOBER,
                ChannelItem.SMS, BigDecimal.ONE, null, null)))).isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asWith(finance, EnumSet.of(Permission.COST_MANAGE, Permission.COST_VIEW),
                Set.of(creditorA), () -> invoices.save(new SaveInvoiceCommand(OCTOBER, ChannelItem.SMS, BigDecimal.ONE,
                        null, null)))).isInstanceOf(ScopeAccessDeniedException.class);
        // mês que ainda não começou
        YearMonth next = YearMonth.now(ZONE).plusMonths(1);
        assertThatThrownBy(() -> asFinance(() -> invoices.save(new SaveInvoiceCommand(next, ChannelItem.SMS,
                BigDecimal.ONE, null, null)))).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("costing.invoice_future_month"));
        // mês com mensagens apuradas antes da conciliação por item: pede reapurar antes da fatura de mensagens
        run(() -> jdbc.update("""
                insert into costing.cost_daily (tenant_id, day, creditor_id, portfolio_id, category, item, amount,
                                                quantity, unit, basis)
                values (?, date '2026-09-10', ?, null, 'MESSAGING', null, 1, 10, 'UNITS', 'ESTIMATED')""",
                tenant, creditorA));
        assertThatThrownBy(() -> asFinance(() -> invoices.save(new SaveInvoiceCommand(YearMonth.of(2026, 9),
                ChannelItem.WHATSAPP, BigDecimal.TEN, null, null)))).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((DomainException) e).code())
                        .isEqualTo("costing.invoice_month_not_itemized"));
        asFinance(() -> invoices.save(new SaveInvoiceCommand(YearMonth.of(2026, 9), ChannelItem.TELEPHONY,
                BigDecimal.TEN, null, null)));

        // outra transação segura o lock da apuração da assessoria (como o job da madrugada)
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> run(() -> {
                jdbc.queryForObject("""
                        select 1 from (select pg_advisory_xact_lock(hashtext('costing.refresh'),
                                                                    hashtext(shared.current_tenant_id()::text))) l""",
                        Integer.class);
                locked.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> asFinance(() -> invoices.save(new SaveInvoiceCommand(OCTOBER,
                    ChannelItem.TELEPHONY, BigDecimal.TEN, null, null)))).isInstanceOf(ConflictException.class)
                    .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("costing.refresh_running"));
            mvc.perform(post("/api/v1/costing/invoices").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"month\":\"2026-10\",\"item\":\"SMS\",\"amount\":1.50}")
                            .with(authentication(auth(finance, DefaultRoles.FINANCEIRO))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("costing.refresh_running"));
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        // recusadas, as faturas não foram gravadas
        assertThat(asFinance(() -> invoices.invoices(OCTOBER, OCTOBER))).isEmpty();

        // contrato HTTP: gravação, listas, base na margem e validação
        mvc.perform(post("/api/v1/costing/invoices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"month\":\"2026-10\",\"item\":\"SMS\",\"amount\":1.50,\"supplier\":\"Gateway\"}")
                        .with(authentication(auth(finance, DefaultRoles.FINANCEIRO))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-10"))
                .andExpect(jsonPath("$.item").value("SMS"))
                .andExpect(jsonPath("$.estimated").value(0.10))
                .andExpect(jsonPath("$.adjustment").value(1.40));
        mvc.perform(get("/api/v1/costing/invoices").param("from", "2026-10").param("to", "2026-10")
                        .with(authentication(auth(manager, DefaultRoles.GESTOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].supplier").value("Gateway"));
        mvc.perform(get("/api/v1/costing/invoices/estimates").param("from", "2026-10").param("to", "2026-10")
                        .with(authentication(auth(manager, DefaultRoles.GESTOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(ChannelItem.values().length));
        mvc.perform(get("/api/v1/costing/margin").param("from", FROM.toString()).param("to", TO.toString())
                        .param("basis", "RECONCILED").with(authentication(auth(manager, DefaultRoles.GESTOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("RECONCILED"))
                .andExpect(jsonPath("$.reconciliation.status").value("PARTIAL"))
                .andExpect(jsonPath("$.reconciliation.months[0].status").value("PARTIAL"))
                .andExpect(jsonPath("$.reconciliation.adjustments.MESSAGING").value(1.40))
                .andExpect(jsonPath("$.totals.costs.MESSAGING").value(1.90));
        mvc.perform(get("/api/v1/costing/margin").param("from", FROM.toString()).param("to", TO.toString())
                        .with(authentication(auth(manager, DefaultRoles.GESTOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("ESTIMATED"))
                .andExpect(jsonPath("$.totals.costs.MESSAGING").value(0.50));
        mvc.perform(get("/api/v1/costing/margin").param("basis", "FATURA")
                        .with(authentication(auth(manager, DefaultRoles.GESTOR))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/costing/invoices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"month\":\"2026-10\",\"item\":\"SMS\",\"amount\":1,\"note\":\"" + "x".repeat(501)
                                + "\"}")
                        .with(authentication(auth(finance, DefaultRoles.FINANCEIRO))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/costing/invoices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"month\":\"2026-10\",\"item\":\"SMS\",\"amount\":1}")
                        .with(authentication(auth(manager, DefaultRoles.GESTOR))))
                .andExpect(status().isForbidden());
    }

    // ============================================================================ dados

    private void seed() {
        String slug = "faturas-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> provisioning.provision(
                new ProvisionTenantCommand(slug, "Assessoria Faturas Ltda", "Faturas", "11.444.777/0001-61", "Admin",
                        "admin@" + slug + ".test"))).tenant().id();
        Map<String, UUID> roleIds = system(() -> roles.list().stream()
                .collect(Collectors.toMap(RoleManagementUseCases.RoleView::code, RoleManagementUseCases.RoleView::id)));
        UUID operator = user("Operador O", roleIds.get(DefaultRoles.OPERADOR));
        manager = user("Gestor G", roleIds.get(DefaultRoles.GESTOR));
        finance = user("Financeiro F", roleIds.get(DefaultRoles.FINANCEIRO));
        run(() -> {
            creditorA = creditor("Credor A");
            creditorB = creditor("Credor B");
            portfolioA = portfolio(creditorA, "FA");
            portfolioB = portfolio(creditorB, "FB");
            UUID debtor = debtor();
            UUID caseA = collectionCase(debtor, creditorA, portfolioA);
            UUID caseB = collectionCase(debtor, creditorB, portfolioB);
            // telefonia a R$ 1/min: 2 min em A (61 s) e 1 min em B (30 s)
            UUID account = providerAccount(BigDecimal.ONE);
            call(account, debtor, creditorA, portfolioA, caseA, operator, at(10, 10), 61);
            call(account, debtor, creditorB, portfolioB, caseB, operator, at(11, 10), 30);
            // uma conversa de WhatsApp em A e um SMS em B
            UUID whatsapp = channelAccount("WHATSAPP", creditorA);
            UUID template = template(creditorA);
            message(conversation(debtor, creditorA, portfolioA, "WHATSAPP", whatsapp), "WHATSAPP", whatsapp, template,
                    at(12, 9));
            UUID sms = channelAccount("SMS", creditorB);
            message(conversation(debtor, creditorB, portfolioB, "SMS", sms), "SMS", sms, null, at(12, 10));
        });
        asFinance(() -> {
            settings.createTariff(new CreateTariffCommand(TariffChannel.WHATSAPP_UTILITY, new BigDecimal("0.40"), FROM));
            return settings.createTariff(new CreateTariffCommand(TariffChannel.SMS, new BigDecimal("0.10"), FROM));
        });
    }

    private ActorAuthentication auth(UUID userId, String role) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst()
                .orElseThrow().permissions();
        return ActorAuthentication.of(new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), role,
                EnumSet.copyOf(permissions), CreditorScope.ALL));
    }

    private UUID user(String name, UUID roleId) {
        return system(() -> userManagement.create(new CreateUserCommand(name, UUID.randomUUID().toString()
                .substring(0, 8) + "@teste.com", null, Set.of(roleId), true, Set.of())).id());
    }

    private <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    private void run(Runnable action) {
        tenantExecution.inTenant(tenant, "test", action);
    }

    private <T> T asFinance(Supplier<T> action) {
        return asRole(finance, DefaultRoles.FINANCEIRO, null, action);
    }

    private <T> T asManager(Set<UUID> creditorIds, Supplier<T> action) {
        return asRole(manager, DefaultRoles.GESTOR, creditorIds, action);
    }

    private <T> T asRole(UUID userId, String role, Set<UUID> creditorIds, Supplier<T> action) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst()
                .orElseThrow().permissions();
        return asWith(userId, permissions, creditorIds, action);
    }

    private <T> T asWith(UUID userId, Set<Permission> permissions, Set<UUID> creditorIds, Supplier<T> action) {
        CreditorScope scope = creditorIds == null ? CreditorScope.ALL : CreditorScope.of(creditorIds);
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), "TEST",
                EnumSet.copyOf(permissions), scope), action);
    }

    private static CreditorMargin creditor(MarginReport report, UUID id) {
        return report.creditors().stream().filter(c -> c.creditorId().equals(id)).findFirst().orElseThrow();
    }

    private static BigDecimal cost(CreditorMargin creditor, CostCategory category) {
        return creditor.line().costs().get(category);
    }

    private static Map<String, BigDecimal> warnings(MarginReport report) {
        return report.warnings().stream().collect(Collectors.toMap(MarginWarning::code, MarginWarning::count));
    }

    static Instant at(int day, int hour) {
        return LocalDateTime.of(2026, 10, day, hour, 0).atZone(ZONE).toInstant();
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private UUID creditor(String name) {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        jdbc.update("""
                insert into organization.creditor (id, tenant_id, version, created_at, updated_at, legal_name,
                    trade_name, document, status) values (?, ?, 0, ?, ?, ?, ?, ?, 'ACTIVE')""",
                id, tenant, ts(now), ts(now), name + " S.A.", name, String.format("%014d", Math.abs(id.hashCode())));
        return id;
    }

    private UUID portfolio(UUID creditorId, String code) {
        UUID contract = Ids.newId();
        UUID id = Ids.newId();
        Instant now = Instant.now();
        jdbc.update("""
                insert into organization.service_contract (id, tenant_id, version, created_at, updated_at, creditor_id,
                    number, start_date, settlement_day, status)
                values (?, ?, 0, ?, ?, ?, ?, date '2026-01-01', 5, 'ACTIVE')""",
                contract, tenant, ts(now), ts(now), creditorId, "K-" + code);
        jdbc.update("""
                insert into organization.portfolio (id, tenant_id, version, created_at, updated_at, creditor_id,
                    contract_id, code, name, received_on, status)
                values (?, ?, 0, ?, ?, ?, ?, ?, ?, date '2026-01-01', 'ACTIVE')""",
                id, tenant, ts(now), ts(now), creditorId, contract, code, "Carteira " + code);
        return id;
    }

    private UUID debtor() {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        jdbc.update("""
                insert into debt.debtor (id, tenant_id, version, created_at, updated_at, person_type, name,
                    document_encrypted, document_hash, status)
                values (?, ?, 0, ?, ?, 'INDIVIDUAL', 'Devedor fictício', 'cifrado', ?, 'ACTIVE')""",
                id, tenant, ts(now), ts(now), id.toString().replace("-", ""));
        return id;
    }

    private UUID collectionCase(UUID debtorId, UUID creditorId, UUID portfolioId) {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        jdbc.update("""
                insert into collection.collection_case (id, tenant_id, version, created_at, updated_at, debtor_id,
                    creditor_id, portfolio_id, status, opened_at, open_balance, eligible_balance, open_titles,
                    suspended_titles, disputed_titles, oldest_due_date, balance_updated_at, eligible, owner_type,
                    interaction_count, effective_contact_count)
                values (?, ?, 0, ?, ?, ?, ?, ?, 'OPEN', ?, 100, 100, 1, 0, 0, date '2026-06-01', ?, true, 'QUEUE', 0, 0)""",
                id, tenant, ts(now), ts(now), debtorId, creditorId, portfolioId, ts(now), ts(now));
        return id;
    }

    private UUID providerAccount(BigDecimal rate) {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        jdbc.update("""
                insert into telephony.provider_account (id, tenant_id, version, created_at, updated_at, provider, name,
                    caller_id, routing_key, recording_enabled, recording_notice, active, rate_per_minute)
                values (?, ?, 0, ?, ?, 'SANDBOX', 'Conta de teste', '1130000000', ?, false, false, true, ?)""",
                id, tenant, ts(now), ts(now), "tk-" + id, rate);
        return id;
    }

    private void call(UUID account, UUID debtorId, UUID creditorId, UUID portfolioId, UUID caseId, UUID operatorId,
                      Instant at, int talk) {
        jdbc.update("""
                insert into telephony.phone_call (id, tenant_id, version, created_at, updated_at, direction, origin,
                    status, result, provider_account_id, provider, creditor_id, portfolio_id, debtor_id, case_id,
                    operator_id, attempt_number, number_masked, queued_at, dial_started_at, answered_at, ended_at,
                    talk_seconds, wrap_up_seconds, abandoned, recording_requested, recording_announced,
                    interaction_state)
                values (?, ?, 0, ?, ?, 'OUTBOUND', 'CLICK_TO_CALL', 'COMPLETED', 'ANSWERED', ?, 'SANDBOX', ?, ?, ?, ?,
                        ?, 1, '(11) *****-4321', ?, ?, ?, ?, ?, 0, false, false, false, 'NONE')""",
                Ids.newId(), tenant, ts(at), ts(at), account, creditorId, portfolioId, debtorId, caseId, operatorId,
                ts(at), ts(at), ts(at), ts(at.plusSeconds(talk)), talk);
    }

    private UUID channelAccount(String channel, UUID creditorId) {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        jdbc.update("""
                insert into communication.channel_account (id, tenant_id, version, created_at, updated_at, channel,
                    provider, creditor_id, name, sender_address, routing_key, active)
                values (?, ?, 0, ?, ?, ?, 'SANDBOX', ?, 'Conta de teste', 'remetente@exemplo.com', ?, true)""",
                id, tenant, ts(now), ts(now), channel, creditorId, "rk-" + id);
        return id;
    }

    private UUID template(UUID creditorId) {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        jdbc.update("""
                insert into communication.message_template (id, tenant_id, version, created_at, updated_at, creditor_id,
                    code, template_version, channel, name, purpose, body, variables, language, status,
                    approval_status, provider_category)
                values (?, ?, 0, ?, ?, ?, 'LEMBRETE', 1, 'WHATSAPP', 'Lembrete', 'REMINDER', 'Olá', '[]'::jsonb, 'pt_BR',
                        'DRAFT', 'PENDING', 'UTILITY')""",
                id, tenant, ts(now), ts(now), creditorId);
        return id;
    }

    private UUID conversation(UUID debtorId, UUID creditorId, UUID portfolioId, String channel, UUID account) {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        jdbc.update("""
                insert into communication.conversation (id, tenant_id, version, created_at, updated_at, debtor_id,
                    creditor_id, portfolio_id, channel, channel_account_id, contact_hash, contact_masked,
                    contact_encrypted, status, owner_type, owner_since, last_message_at, unread_count, message_count)
                values (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, '(11) *****-4321', 'cifrado', 'OPEN', 'QUEUE', ?, ?, 0, 1)""",
                id, tenant, ts(now), ts(now), debtorId, creditorId, portfolioId, channel, account, "h" + id, ts(now),
                ts(now));
        return id;
    }

    private void message(UUID conversationId, String channel, UUID account, UUID templateId, Instant at) {
        jdbc.update("""
                insert into communication.message (id, tenant_id, version, created_at, updated_at, conversation_id,
                    channel, channel_account_id, direction, sender_type, template_id, status, status_at, send_attempts,
                    queued_at, sent_at)
                values (?, ?, 0, ?, ?, ?, ?, ?, 'OUTBOUND', 'CAMPAIGN', ?, 'SENT', ?, 1, ?, ?)""",
                Ids.newId(), tenant, ts(at), ts(at), conversationId, channel, account, templateId, ts(at), ts(at),
                ts(at));
    }
}
