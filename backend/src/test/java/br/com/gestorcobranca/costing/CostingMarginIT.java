package br.com.gestorcobranca.costing;

import br.com.gestorcobranca.costing.application.port.in.CostRefreshUseCases;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.AssignUserBandCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.CreateBandCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.CreateOverheadCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.CreateTariffCommand;
import br.com.gestorcobranca.costing.application.port.in.CostSettingsUseCases.SaveFxRateCommand;
import br.com.gestorcobranca.costing.application.port.in.MarginExportUseCases;
import br.com.gestorcobranca.costing.application.port.in.MarginExportUseCases.ExportFile;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.CreditorMargin;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.MarginFilter;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.MarginReport;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.MarginTrend;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.TrendMonth;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.TrendPoint;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.MarginWarning;
import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.MarginLine;
import br.com.gestorcobranca.costing.domain.model.OverheadAllocation;
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
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.incentive.CommissionStatementsChanged;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Apuração ponta a ponta (desenho margem-por-carteira §4 e §10): cadastros pelos casos de uso, uso gravado direto nas
 * tabelas dos módulos de origem (o costing lê o banco pelas views de contrato), reapuração de outubro/2026 e o painel
 * com escopo por credor, avisos, exportação e o formato JSON do contrato.
 */
class CostingMarginIT extends IntegrationTest {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    static final LocalDate TO = LocalDate.of(2026, 10, 31);

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
    ApplicationEventPublisher publisher;
    @Autowired
    List<JobHandler<?>> jobHandlers;
    @Autowired
    CostSettingsUseCases settings;
    @Autowired
    CostRefreshUseCases refresh;
    @Autowired
    MarginQueries margins;
    @Autowired
    MarginExportUseCases exports;
    @Autowired
    JsonMapper jsonMapper;
    @Autowired
    WebApplicationContext webApplicationContext;

    UUID tenant;
    UUID operatorX;
    UUID operatorY;
    UUID manager;
    UUID finance;
    UUID creditorA;
    UUID creditorB;
    UUID portfolioA;
    UUID portfolioB;

    @Test
    void apuraOsCustosERespondeAMargemComEscopoPorCredor() throws Exception {
        seed();
        configure();
        CostRefreshUseCases.RefreshResult result = asRole(finance, DefaultRoles.FINANCEIRO, null,
                () -> refresh.refresh(FROM, TO));
        assertThat(result.rows()).isPositive();
        // reapurar não duplica
        asRole(finance, DefaultRoles.FINANCEIRO, null, () -> refresh.refresh(FROM, TO));
        // a reapuração manual fica na auditoria, com período e linhas
        assertThat(system(() -> jdbc.queryForList("""
                select metadata ->> 'from' from shared.audit_event where action = 'costing.refreshed'""",
                String.class))).containsExactly("2026-10-01", "2026-10-01");

        MarginReport all = asRole(manager, DefaultRoles.GESTOR, null,
                () -> margins.margin(new MarginFilter(FROM, TO, null, null)));
        CreditorMargin a = creditor(all, creditorA);
        CreditorMargin b = creditor(all, creditorB);

        // A: X 1 h direto (36) + Y 0,5 h sem faixa (0) + pós-atendimento de X 0,2 h (7,20); ociosidade de X 1 h (36)
        assertThat(cost(a.line(), CostCategory.HUMAN_DIRECT)).isEqualByComparingTo("43.20");
        assertThat(a.line().directHours()).isEqualByComparingTo("1.70");
        assertThat(cost(a.line(), CostCategory.HUMAN_IDLE)).isEqualByComparingTo("36.00");
        // chamada de 61 s = 2 minutos × R$ 0,10
        assertThat(cost(a.line(), CostCategory.TELEPHONY)).isEqualByComparingTo("0.20");
        // dois templates de utilidade em 10 h na mesma conversa = uma conversa cobrada
        assertThat(cost(a.line(), CostCategory.MESSAGING)).isEqualByComparingTo("0.04");
        // horas: A 2,5 h (dia 15) + 0,2 h (dia 16) = 2,7 de 3,7 h com credor
        assertThat(cost(a.line(), CostCategory.OVERHEAD)).isEqualByComparingTo("729.73");
        assertThat(a.line().revenue()).isEqualByComparingTo("200.00");
        assertThat(a.line().recovered()).isEqualByComparingTo("1000.00");
        assertThat(a.line().directCost()).isEqualByComparingTo("43.44");
        assertThat(a.line().contributionMargin()).isEqualByComparingTo("156.56");
        assertThat(a.line().netMargin()).isEqualByComparingTo("-609.17");
        assertThat(a.line().contributionMarginRate()).isEqualByComparingTo("0.7828");
        assertThat(a.portfolios()).extracting(MarginQueries.PortfolioMargin::portfolioId).containsExactly(portfolioA);
        assertThat(a.name()).isEqualTo("Credor A");

        assertThat(cost(b.line(), CostCategory.HUMAN_DIRECT)).isEqualByComparingTo("18.00");
        assertThat(cost(b.line(), CostCategory.HUMAN_IDLE)).isEqualByComparingTo("18.00");
        assertThat(cost(b.line(), CostCategory.MESSAGING)).isEqualByComparingTo("0.08");
        assertThat(cost(b.line(), CostCategory.OVERHEAD)).isEqualByComparingTo("270.27");
        assertThat(b.line().revenue()).isZero();
        assertThat(b.line().contributionMarginRate()).isNull();

        // Y logado sem trabalho direto no dia 17: ociosidade sem credor (sem faixa: 1 h a custo zero)
        assertThat(all.unallocated()).isNotNull();
        assertThat(all.unallocated().idleHours()).isEqualByComparingTo("1.00");
        assertThat(all.totals().totalCost()).isEqualByComparingTo(
                a.line().totalCost().add(b.line().totalCost()).add(all.unallocated().totalCost()));
        assertThat(cost(all.totals(), CostCategory.OVERHEAD)).isEqualByComparingTo("1000.00");
        assertThat(all.months()).extracting(MarginQueries.MonthMargin::month).containsExactly("2026-10");
        assertThat(all.computedUntil()).isEqualTo(TO);
        assertThat(warnings(all)).containsEntry("HOURS_WITHOUT_BAND", new BigDecimal("1.5"))
                .doesNotContainKey("NOT_REFRESHED");

        // escopo restrito ao credor B: só as linhas de B, sem linha sem credor e sem avisos de outros credores
        MarginReport onlyB = asRole(manager, DefaultRoles.GESTOR, Set.of(creditorB),
                () -> margins.margin(new MarginFilter(FROM, TO, Set.of(creditorA, creditorB), null)));
        assertThat(onlyB.creditors()).extracting(CreditorMargin::creditorId).containsExactly(creditorB);
        assertThat(onlyB.unallocated()).isNull();
        assertThat(onlyB.totals().totalCost()).isEqualByComparingTo(b.line().totalCost());
        assertThat(warnings(onlyB)).doesNotContainKey("HOURS_WITHOUT_BAND");
        // pedido inteiramente fora do escopo: nada
        assertThat(asRole(manager, DefaultRoles.GESTOR, Set.of(creditorB),
                () -> margins.margin(new MarginFilter(FROM, TO, Set.of(creditorA), null))).creditors()).isEmpty();
        // escopo restrito não vê o custo fixo da assessoria na lista de premissas
        assertThat(asRole(manager, DefaultRoles.GESTOR, Set.of(creditorB), () -> settings.overheads(null, null)))
                .isEmpty();

        // período sem apuração avisa
        MarginReport november = asRole(manager, DefaultRoles.GESTOR, null,
                () -> margins.margin(new MarginFilter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10), null, null)));
        assertThat(warnings(november)).containsEntry("NOT_REFRESHED", BigDecimal.TEN);

        // contrato JSON (§10.2): Line achatada nos credores, carteiras e meses; mês AAAA-MM; custos por categoria
        JsonNode json = jsonMapper.readTree(jsonMapper.writeValueAsString(all));
        assertThat(json.get("period").get("from").asString()).isEqualTo("2026-10-01");
        assertThat(json.get("basis").asString()).isEqualTo("ESTIMATED");
        JsonNode first = json.get("creditors").get(0);
        assertThat(first.has("creditorId")).isTrue();
        assertThat(first.has("revenue")).isTrue();
        assertThat(first.has("line")).isFalse();
        assertThat(first.get("costs").has("HUMAN_DIRECT")).isTrue();
        assertThat(first.get("portfolios").get(0).has("netMargin")).isTrue();
        assertThat(json.get("months").get(0).get("month").asString()).isEqualTo("2026-10");
        assertThat(json.get("warnings").get(0).has("code")).isTrue();
        assertThat(json.get("warnings").get(0).has("count")).isTrue();

        // exportação XLSX e CSV (premissas pelo nome da faixa, nunca a faixa de uma pessoa) com log de acesso
        ExportFile xlsx = asRole(manager, DefaultRoles.GESTOR, null,
                () -> exports.export(new MarginFilter(FROM, TO, null, null), "xlsx"));
        List<String> sheets = new ArrayList<>();
        String assumptions;
        String summary;
        List<String> bandRows = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(xlsx.content())) {
            wb.forEach(s -> sheets.add(s.getSheetName()));
            assumptions = sheetText(wb, "Premissas");
            summary = sheetText(wb, "Resumo");
            wb.getSheet("Premissas").forEach(r -> {
                if (r.getCell(0).toString().startsWith("Faixa de custo")) {
                    StringBuilder sb = new StringBuilder();
                    r.forEach(c -> sb.append(c.toString()).append('|'));
                    bandRows.add(sb.toString());
                }
            });
        }
        assertThat(sheets).containsExactly("Resumo", "Por credor", "Por carteira", "Por mês", "Premissas");
        assertThat(assumptions).contains("Negociador júnior").doesNotContain("Operador X");
        // sem COST_RATE_MANAGE: a faixa aparece só pelo nome, sem o valor-hora (LGPD)
        assertThat(bandRows).singleElement()
                .isEqualTo("Faixa de custo de pessoas|Negociador júnior|2026-01-01|");
        // avisos com rótulo em português, sem o código interno
        assertThat(summary).contains("Aviso: Horas de trabalho sem faixa de custo").doesNotContain("HOURS_WITHOUT_BAND");
        ExportFile csv = asRole(manager, DefaultRoles.GESTOR, null,
                () -> exports.export(new MarginFilter(FROM, TO, null, null), "csv"));
        String csvText = new String(csv.content().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csvText).contains("secao;chave;rotulo;indicador;valor").contains("margem_liquida")
                .contains("avisos;HOURS_WITHOUT_BAND;Horas de trabalho sem faixa de custo")
                .contains("Negociador júnior;inicio_vigencia;2026-01-01").doesNotContain("valor_hora");
        // o valor-hora só para quem gerencia as faixas (COST_RATE_MANAGE), na lista e na exportação
        assertThat(asRole(manager, DefaultRoles.GESTOR, null, () -> settings.bands()))
                .allSatisfy(band -> assertThat(band.hourlyCost()).isNull());
        assertThat(asRole(finance, DefaultRoles.FINANCEIRO, null, () -> settings.bands()))
                .singleElement().satisfies(band -> assertThat(band.hourlyCost()).isEqualByComparingTo("36.00"));
        String financeCsv = new String(asRole(finance, DefaultRoles.FINANCEIRO, null,
                () -> exports.export(new MarginFilter(FROM, TO, null, null), "csv")).content().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(financeCsv).contains("Negociador júnior;valor_hora;36");
        List<Map<String, Object>> logs = system(() -> jdbc.queryForList("""
                select details ->> 'format' as format from shared.access_log
                 where event_type = 'DATA_EXPORTED' and actor_id = ? and details ->> 'resource' = 'costing_margin'
                 order by occurred_at""", manager));
        assertThat(system(() -> jdbc.queryForObject("""
                select details ->> 'generatedOn' from shared.access_log
                 where event_type = 'DATA_EXPORTED' and actor_id = ? limit 1""", String.class, manager)))
                .isEqualTo(LocalDate.now(ZONE).toString());
        assertThat(logs).extracting(m -> m.get("format")).containsExactly("XLSX", "CSV");
        // exportar exige REPORT_EXPORT além de COST_VIEW
        assertThatThrownBy(() -> asWith(manager, EnumSet.of(Permission.COST_VIEW), null,
                () -> exports.export(new MarginFilter(FROM, TO, null, null), "csv")))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // os fatos não guardam pessoa e respeitam a assessoria
        assertThat(system(() -> jdbc.queryForObject("select count(*) from costing.cost_daily", Long.class))).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from costing.cost_daily", Long.class)).isZero();
    }

    @Test
    void tendenciaDeSeisMesesPorCredorComMesSemDadoEEscopo() throws Exception {
        seed();
        configure();
        // setembro: só um recebimento em B (R$ 1.000, receita 200); outubro como no cenário principal
        run(() -> receiptWithCommission(creditorB, portfolioB, LocalDate.of(2026, 9, 10)));
        asRole(finance, DefaultRoles.FINANCEIRO, null, () -> refresh.refresh(LocalDate.of(2026, 9, 1), TO));
        MarginReport october = asRole(manager, DefaultRoles.GESTOR, null,
                () -> margins.margin(new MarginFilter(FROM, TO, null, null)));

        // o início do período não muda a janela: 6 meses até o mês do fim
        MarginTrend trend = asRole(manager, DefaultRoles.GESTOR, null,
                () -> margins.trend(new MarginFilter(LocalDate.of(2026, 10, 20), TO, null, null)));
        assertThat(trend.months()).extracting(TrendMonth::month)
                .containsExactly("2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10");
        assertThat(trend.months()).extracting(TrendMonth::status).containsOnly("COMPLETE");
        assertThat(trend.computedUntil()).isEqualTo(TO);
        assertThat(trend.creditors()).extracting(MarginQueries.CreditorTrend::creditorId)
                .containsExactlyInAnyOrder(creditorA, creditorB);

        // A: meses sem fato ficam vazios (nunca zero); outubro igual ao painel
        List<TrendPoint> a = trend(trend, creditorA);
        assertThat(a).hasSize(6);
        assertThat(a.subList(0, 5)).allSatisfy(p -> {
            assertThat(p.netMargin()).isNull();
            assertThat(p.revenue()).isNull();
            assertThat(p.netMarginRate()).isNull();
        });
        assertThat(a.get(5).netMargin()).isEqualByComparingTo(creditor(october, creditorA).line().netMargin());
        assertThat(a.get(5).netMarginRate()).isEqualByComparingTo(creditor(october, creditorA).line().netMarginRate());
        assertThat(trend.creditors().stream().filter(c -> c.creditorId().equals(creditorA)).findFirst().orElseThrow()
                .portfolios()).extracting(MarginQueries.PortfolioTrend::portfolioId).contains(portfolioA);

        // B: setembro só receita (margem 100%); outubro só custo (margem negativa, taxa sem base)
        List<TrendPoint> b = trend(trend, creditorB);
        assertThat(b.get(4).revenue()).isEqualByComparingTo("200.00");
        assertThat(b.get(4).recovered()).isEqualByComparingTo("1000.00");
        assertThat(b.get(4).netMargin()).isEqualByComparingTo("200.00");
        assertThat(b.get(4).netMarginRate()).isEqualByComparingTo("1");
        assertThat(b.get(5).revenue()).isZero();
        assertThat(b.get(5).netMargin()).isEqualByComparingTo(creditor(october, creditorB).line().netMargin());
        assertThat(b.get(5).netMarginRate()).isNull();

        // mês ainda não apurado: situação e ponto vazio, mesmo com o mês anterior completo
        MarginTrend november = asRole(manager, DefaultRoles.GESTOR, null, () -> margins.trend(
                new MarginFilter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10), null, null)));
        assertThat(november.months().getLast()).isEqualTo(new TrendMonth("2026-11", "NOT_REFRESHED"));
        assertThat(trend(november, creditorA).getLast().netMargin()).isNull();
        assertThat(trend(november, creditorA).get(4).netMargin()).isNotNull();

        // escopo restrito: só os credores do usuário, mesmo pedindo outro; fora do escopo, nada
        MarginTrend onlyA = asRole(manager, DefaultRoles.GESTOR, Set.of(creditorA),
                () -> margins.trend(new MarginFilter(FROM, TO, Set.of(creditorA, creditorB), null)));
        assertThat(onlyA.creditors()).extracting(MarginQueries.CreditorTrend::creditorId).containsExactly(creditorA);
        assertThat(asRole(manager, DefaultRoles.GESTOR, Set.of(creditorA),
                () -> margins.trend(new MarginFilter(FROM, TO, Set.of(creditorB), null))).creditors()).isEmpty();
        // filtro de carteira: só a carteira pedida
        MarginTrend byPortfolio = asRole(manager, DefaultRoles.GESTOR, null,
                () -> margins.trend(new MarginFilter(FROM, TO, null, Set.of(portfolioB))));
        assertThat(byPortfolio.creditors()).extracting(MarginQueries.CreditorTrend::creditorId)
                .containsExactly(creditorB);
        // sem COST_VIEW, negado
        assertThatThrownBy(() -> asWith(manager, EnumSet.of(Permission.REPORT_EXPORT), null,
                () -> margins.trend(new MarginFilter(FROM, TO, null, null))))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // HTTP: contrato JSON (pontos vazios como null) e 403 sem COST_VIEW
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        mvc.perform(get("/api/v1/costing/margin/trend").param("from", FROM.toString()).param("to", TO.toString())
                        .with(authentication(auth(manager, DefaultRoles.GESTOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months[5].month").value("2026-10"))
                .andExpect(jsonPath("$.months[5].status").value("COMPLETE"))
                .andExpect(jsonPath("$.creditors[0].points.length()").value(6))
                .andExpect(jsonPath("$.creditors[0].points[0].month").value("2026-05"));
        mvc.perform(get("/api/v1/costing/margin/trend").param("from", FROM.toString()).param("to", TO.toString())
                        .with(authentication(auth(operatorX, DefaultRoles.OPERADOR))))
                .andExpect(status().isForbidden());
    }

    private static List<TrendPoint> trend(MarginTrend trend, UUID creditorId) {
        return trend.creditors().stream().filter(c -> c.creditorId().equals(creditorId)).findFirst().orElseThrow()
                .points();
    }

    @Test
    void cadastrosValidamPermissaoVigenciaEPessoa() {
        seed();
        configure();
        // gestor consulta, mas não altera tarifas nem faixas
        assertThat(asRole(manager, DefaultRoles.GESTOR, null, () -> settings.bands())).hasSize(1);
        assertThatThrownBy(() -> asRole(manager, DefaultRoles.GESTOR, null, () -> settings.createTariff(
                new CreateTariffCommand(TariffChannel.EMAIL, BigDecimal.ZERO, FROM))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asRole(manager, DefaultRoles.GESTOR, null, () -> settings.userBands()))
                .isInstanceOf(ScopeAccessDeniedException.class);
        // nova vigência da faixa encerra a anterior na véspera; sobreposição é recusada
        asRole(finance, DefaultRoles.FINANCEIRO, null, () -> settings.createBand(new CreateBandCommand(
                "Negociador júnior", new BigDecimal("40.00"), LocalDate.of(2026, 11, 1))));
        assertThat(asRole(finance, DefaultRoles.FINANCEIRO, null, () -> settings.bands()))
                .extracting(CostSettingsUseCases.BandView::validTo)
                .containsExactly(LocalDate.of(2026, 10, 31), null);
        assertThatThrownBy(() -> asRole(finance, DefaultRoles.FINANCEIRO, null, () -> settings.createBand(
                new CreateBandCommand("Negociador júnior", new BigDecimal("41.00"), LocalDate.of(2026, 10, 15)))))
                .isInstanceOf(DomainException.class);
        // usuário inexistente na assessoria e faixa desconhecida
        assertThatThrownBy(() -> asRole(finance, DefaultRoles.FINANCEIRO, null, () -> settings.assignUserBand(
                UUID.randomUUID(), new AssignUserBandCommand("Negociador júnior", FROM))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asRole(finance, DefaultRoles.FINANCEIRO, null, () -> settings.assignUserBand(
                operatorY, new AssignUserBandCommand("Inexistente", FROM)))).isInstanceOf(DomainException.class);
        // lista de pessoas inclui quem não tem faixa, sem valor-hora
        List<CostSettingsUseCases.UserBandView> people = asRole(finance, DefaultRoles.FINANCEIRO, null,
                () -> settings.userBands());
        Map<UUID, String> bandByUser = people.stream().filter(p -> p.bandName() != null)
                .collect(Collectors.toMap(CostSettingsUseCases.UserBandView::userId,
                        CostSettingsUseCases.UserBandView::bandName));
        assertThat(people).extracting(CostSettingsUseCases.UserBandView::userId).contains(operatorX, operatorY);
        assertThat(bandByUser).containsEntry(operatorX, "Negociador júnior").doesNotContainKey(operatorY);
        // nova faixa da pessoa encerra a anterior
        asRole(finance, DefaultRoles.FINANCEIRO, null, () -> settings.assignUserBand(operatorX,
                new AssignUserBandCommand(null, LocalDate.of(2026, 12, 1))));
        assertThat(asRole(finance, DefaultRoles.FINANCEIRO, null, () -> settings.userBandHistory(operatorX)))
                .singleElement().satisfies(v -> assertThat(v.validTo()).isEqualTo(LocalDate.of(2026, 11, 30)));
        // custo fixo: excluir refaz o rateio; auditoria das alterações
        UUID overheadId = asRole(finance, DefaultRoles.FINANCEIRO, null, () -> settings.overheads(null, null))
                .getFirst().id();
        asRole(finance, DefaultRoles.FINANCEIRO, null, () -> {
            settings.deleteOverhead(overheadId, "lançado em duplicidade");
            return null;
        });
        assertThat(system(() -> jdbc.queryForObject("""
                select count(*) from costing.cost_daily where category = 'OVERHEAD'""", Long.class))).isZero();
        assertThat(system(() -> jdbc.queryForList("select action from shared.audit_event where action like 'costing.%'",
                String.class))).contains("costing.band_created", "costing.band_ended", "costing.user_band_assigned",
                "costing.user_band_ended", "costing.tariff_created", "costing.fx_rate_created",
                "costing.overhead_created", "costing.overhead_deleted");
    }

    @Test
    void rotasNaoEsperamAApuracaoEmAndamentoEValidamOsParametros() throws Exception {
        seed();
        configure();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
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
            // a reapuração manual e o rateio do custo fixo falham na hora, sem esperar
            assertThatThrownBy(() -> asRole(finance, DefaultRoles.FINANCEIRO, null, () -> refresh.refresh(FROM, TO)))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("costing.refresh_running"));
            assertThatThrownBy(() -> asRole(finance, DefaultRoles.FINANCEIRO, null, () -> settings.createOverhead(
                    new CreateOverheadCommand(YearMonth.of(2026, 10), "Licenças", new BigDecimal("50.00"),
                            OverheadAllocation.HOURS, null))))
                    .isInstanceOf(ConflictException.class);
            mvc.perform(post("/api/v1/costing/refresh").param("from", FROM.toString()).param("to", TO.toString())
                            .with(authentication(auth(finance, DefaultRoles.FINANCEIRO))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("costing.refresh_running"));
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        // liberado o lock, a reapuração segue; o custo fixo recusado não foi gravado
        assertThat(asRole(finance, DefaultRoles.FINANCEIRO, null, () -> refresh.refresh(FROM, TO)).rows()).isPositive();
        assertThat(asRole(finance, DefaultRoles.FINANCEIRO, null, () -> settings.overheads(null, null))).hasSize(1);
        // o dia de hoje ainda não fechou: a reapuração vai até ontem
        LocalDate today = LocalDate.now(ZONE);
        assertThatThrownBy(() -> asRole(finance, DefaultRoles.FINANCEIRO, null, () -> refresh.refresh(today, today)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("costing.refresh_future"));
        // período acima do limite (93 dias)
        assertThatThrownBy(() -> asRole(finance, DefaultRoles.FINANCEIRO, null,
                () -> refresh.refresh(TO.minusDays(93), TO)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("costing.refresh_too_long"));

        // limites dos parâmetros por HTTP: 400 da validação do Spring MVC (nunca erro interno nem o pedido aceito)
        UUID overheadId = asRole(finance, DefaultRoles.FINANCEIRO, null, () -> settings.overheads(null, null))
                .getFirst().id();
        mvc.perform(delete("/api/v1/costing/overheads/{id}", overheadId).param("reason", "x".repeat(501))
                        .with(authentication(auth(finance, DefaultRoles.FINANCEIRO))))
                .andExpect(status().isBadRequest());
        MockHttpServletRequestBuilder tooMany = get("/api/v1/costing/margin").param("from", FROM.toString())
                .param("to", TO.toString()).with(authentication(auth(manager, DefaultRoles.GESTOR)));
        for (int i = 0; i < 201; i++) {
            tooMany.param("creditorId", UUID.randomUUID().toString());
        }
        mvc.perform(tooMany).andExpect(status().isBadRequest());
        // excluir com motivo (qualquer mês) refaz o rateio e registra o motivo
        mvc.perform(delete("/api/v1/costing/overheads/{id}", overheadId).param("reason", "lançado em duplicidade")
                        .with(authentication(auth(finance, DefaultRoles.FINANCEIRO))))
                .andExpect(status().isNoContent());
        assertThat(system(() -> jdbc.queryForObject("""
                select reason from shared.audit_event where action = 'costing.overhead_deleted'""", String.class)))
                .isEqualTo("lançado em duplicidade");
    }

    // ============================================================================ dados

    private static String sheetText(XSSFWorkbook wb, String sheet) {
        StringBuilder sb = new StringBuilder();
        wb.getSheet(sheet).forEach(r -> r.forEach(c -> sb.append(c.toString()).append('|')));
        return sb.toString();
    }

    private ActorAuthentication auth(UUID userId, String role) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst()
                .orElseThrow().permissions();
        return ActorAuthentication.of(new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), role,
                EnumSet.copyOf(permissions), CreditorScope.ALL));
    }

    /**
     * Desenho §4.1: a mesma pessoa atende ao mesmo tempo uma ligação do credor A (9h às 10h) e uma conversa de chat do
     * credor B (respostas às 9h22 e às 9h50). A hora é cobrada uma vez só: o trecho em comum é dividido entre os dois
     * credores, e cada resposta do chat vale do evento anterior da conversa até o envio (no máximo 3 minutos).
     */
    @Test
    void atendimentosSimultaneosNaoCobramAMesmaHoraDuasVezes() {
        seedTenantAndCreditors();
        run(() -> {
            UUID debtor = debtor();
            UUID caseA = collectionCase(debtor, creditorA, portfolioA);
            interaction(caseA, debtor, creditorA, portfolioA, operatorX, at(20, 9), 3600, tabulation());
            UUID whatsapp = channelAccount("WHATSAPP", creditorB);
            UUID conversation = conversation(debtor, creditorB, portfolioB, "WHATSAPP", whatsapp);
            Instant nine = at(20, 9);
            chat(conversation, whatsapp, false, null, nine.plusSeconds(20 * 60));
            // resposta 2 min depois da mensagem do devedor: 2 min de trabalho
            chat(conversation, whatsapp, true, operatorX, nine.plusSeconds(22 * 60));
            chat(conversation, whatsapp, false, null, nine.plusSeconds(40 * 60));
            // resposta 10 min depois: só os 3 min anteriores ao envio contam
            chat(conversation, whatsapp, true, operatorX, nine.plusSeconds(50 * 60));
            // resposta do assistente não é tempo de pessoa
            chat(conversation, whatsapp, true, null, nine.plusSeconds(55 * 60));
            loggedTime(operatorX, LocalDate.of(2026, 10, 20), 3600);
        });
        asRole(finance, DefaultRoles.FINANCEIRO, null, () -> {
            settings.createBand(new CreateBandCommand("Negociador júnior", new BigDecimal("36.00"),
                    LocalDate.of(2026, 1, 1)));
            settings.assignUserBand(operatorX, new AssignUserBandCommand("Negociador júnior", LocalDate.of(2026, 1, 1)));
            return refresh.refresh(FROM, TO);
        });
        MarginReport all = asRole(manager, DefaultRoles.GESTOR, null,
                () -> margins.margin(new MarginFilter(FROM, TO, null, null)));
        MarginLine a = creditor(all, creditorA).line();
        MarginLine b = creditor(all, creditorB).line();
        // 5 min em comum divididos ao meio: A 55 + 2,5 = 57,5 min; B 2,5 min; a soma é a hora do relógio
        assertThat(cost(a, CostCategory.HUMAN_DIRECT)).isEqualByComparingTo("34.50");
        assertThat(cost(b, CostCategory.HUMAN_DIRECT)).isEqualByComparingTo("1.50");
        assertThat(all.totals().directHours()).isEqualByComparingTo("1.00");
        assertThat(cost(all.totals(), CostCategory.HUMAN_DIRECT)).isEqualByComparingTo("36.00");
        // logada 1 h e trabalhada 1 h: sem ociosidade
        assertThat(cost(all.totals(), CostCategory.HUMAN_IDLE)).isZero();
    }

    @Test
    void comissaoVariavelEOValorDoExtratoRateadoPeloCreditadoEEstimadaSemExtrato() {
        seedTenantAndCreditors();
        UUID u1 = Ids.newId();
        UUID u2 = Ids.newId();
        run(() -> {
            UUID attrA = attribution(creditorA, portfolioA);
            UUID attrB = attribution(creditorB, portfolioB);
            UUID period = commissionPeriod(LocalDate.of(2026, 10, 1));
            // extrato de outubro do U1: R$ 200 a pagar sobre R$ 10.000 creditados → 2%, rateado A 6.000 / B 4.000
            UUID statement = statement(period, u1, LocalDate.of(2026, 10, 1), "200.00", "10000.00");
            UUID first = ledger(u1, attrA, creditorA, portfolioA, "6000.00", LocalDate.of(2026, 10, 5), "CLEARED", statement);
            ledger(u1, attrB, creditorB, portfolioB, "4000.00", LocalDate.of(2026, 10, 12), "CLEARED", statement);
            // ainda sem extrato: estimado pela última taxa do U1 (2%) → B 20
            ledger(u1, attrB, creditorB, portfolioB, "1000.00", LocalDate.of(2026, 10, 20), "PENDING", null);
            // U2 sem extrato algum: taxa do último período da assessoria (200 / 10.000) → A 100
            ledger(u2, attrA, creditorA, portfolioA, "5000.00", LocalDate.of(2026, 10, 25), "PENDING", null);
            // dispensado não é custo
            ledger(u2, attrA, creditorA, portfolioA, "9999.00", LocalDate.of(2026, 10, 26), "WAIVED", null);
            // estorno de 1.000 do 1º lançamento, no dia em que foi lançado → A −20
            reversal(u1, attrA, creditorA, portfolioA, first, "-1000.00", at(28, 10));
            // equipe: 100% da base creditada à equipe nunca recebe extrato; o pool dos membros já está no extrato
            // deles — só o extrato SEM lançamento pessoal (supervisor, R$ 30) é rateado pelo creditado de equipe
            UUID team = Ids.newId();
            ledger(team, "TEAM", attrA, creditorA, portfolioA, "2000.00", LocalDate.of(2026, 10, 5), "CLEARED", null);
            ledger(team, "TEAM", attrB, creditorB, portfolioB, "1000.00", LocalDate.of(2026, 10, 12), "CLEARED", null);
            statement(period, Ids.newId(), LocalDate.of(2026, 10, 1), "30.00", "0.00");
        });
        asRole(finance, DefaultRoles.FINANCEIRO, null, () -> refresh.refresh(FROM, TO));
        MarginReport all = asRole(manager, DefaultRoles.GESTOR, null,
                () -> margins.margin(new MarginFilter(FROM, TO, null, null)));
        // nunca o recuperado creditado (antes: A 22.999, B 6.000); pool do supervisor: A +20, B +10
        assertThat(cost(creditor(all, creditorA).line(), CostCategory.VARIABLE_COMMISSION)).isEqualByComparingTo("220.00");
        assertThat(cost(creditor(all, creditorB).line(), CostCategory.VARIABLE_COMMISSION)).isEqualByComparingTo("110.00");
        // a view não expõe o beneficiário
        assertThat(system(() -> jdbc.queryForList("""
                select column_name from information_schema.columns
                 where table_schema = 'costing' and table_name = 'src_commission_cost'""", String.class)))
                .doesNotContain("beneficiary_id", "credited_amount");
    }

    @Test
    void extratoNovoDoPeriodoReapuraAComissaoForaDaJanelaSemReapuracaoManual() {
        seedTenantAndCreditors();
        LocalDate paidOn = LocalDate.now(ZONE).minusDays(50);
        LocalDate period = paidOn.withDayOfMonth(1);
        UUID u1 = Ids.newId();
        UUID[] entry = new UUID[1];
        UUID[] currentPeriod = new UUID[1];
        run(() -> {
            UUID attrA = attribution(creditorA, portfolioA);
            // extrato anterior do U1: 1% (R$ 10 sobre R$ 1.000)
            UUID previousPeriod = commissionPeriod(period.minusMonths(1));
            UUID previous = statement(previousPeriod, u1, period.minusMonths(1), "10.00", "1000.00");
            ledger(u1, attrA, creditorA, portfolioA, "1000.00", period.minusMonths(1), "CLEARED", previous);
            // ainda sem extrato: estimado a 1% → R$ 50
            entry[0] = ledger(u1, attrA, creditorA, portfolioA, "5000.00", paidOn, "PENDING", null);
            // U3 sem extrato, pago antes (credor B): estimado pela taxa do último período da assessoria (1% → R$ 10)
            ledger(Ids.newId(), attrB(), creditorB, portfolioB, "1000.00", paidOn.minusDays(5), "PENDING", null);
            currentPeriod[0] = commissionPeriod(period);
        });
        asRole(finance, DefaultRoles.FINANCEIRO, null, () -> refresh.refresh(paidOn.minusDays(5), paidOn));
        assertThat(commissionOn(paidOn)).isEqualByComparingTo("50.00");
        assertThat(commissionOn(paidOn.minusDays(5), creditorB)).isEqualByComparingTo("10.00");

        // o período fecha 50 dias depois do pagamento (fora da janela do job diário): extrato de R$ 150 → 3%
        run(() -> {
            UUID statement = statement(currentPeriod[0], u1, period, "150.00", "5000.00");
            jdbc.update("""
                    update incentive.commission_ledger_entry set status = 'CLEARED', cleared_at = now(),
                        period_start = ?, statement_id = ? where id = ?""", period, statement, entry[0]);
            publisher.publishEvent(new CommissionStatementsChanged(Ids.newId(), tenant, Instant.now(), period));
        });
        awaitTrue("reapuração agendada", () -> jobStatus(period) != null);
        assertThat(system(() -> jdbc.queryForObject("""
                select run_at > created_at from shared.processing_job
                 where type = 'costing.commission_refresh' and dedup_key like ?""", Boolean.class,
                "commission:" + period + ":%")))
                .as("espera o fim da janela para juntar fechamento, ajustes e recálculos do período").isTrue();
        // o agendamento fica desligado nos ITs: o teste reivindica e executa o job como o JobWorker, sem esperar a janela
        runCommissionRefreshJob(period);
        assertThat(jobStatus(period)).isEqualTo("DONE");
        assertThat(commissionOn(paidOn)).isEqualByComparingTo("150.00");
        // a estimativa anterior ao período também passa à taxa nova da assessoria (3% → R$ 30)
        assertThat(commissionOn(paidOn.minusDays(5), creditorB)).isEqualByComparingTo("30.00");
    }

    @SuppressWarnings("unchecked")
    private void runCommissionRefreshJob(LocalDate period) {
        Map<String, Object> claimed = system(() -> jdbc.queryForMap("""
                update shared.processing_job set status = 'RUNNING', attempts = attempts + 1, started_at = now()
                 where type = 'costing.commission_refresh' and dedup_key like ? and status = 'PENDING'
                returning id, type, payload::text as payload, attempts""", "commission:" + period + ":%"));
        UUID id = (UUID) claimed.get("id");
        String type = (String) claimed.get("type");
        JobHandler<Object> handler = (JobHandler<Object>) jobHandlers.stream()
                .filter(h -> h.type().equals(type)).findFirst().orElseThrow();
        Object payload = jsonMapper.readValue((String) claimed.get("payload"), handler.payloadType());
        run(() -> {
            handler.handle(new JobContext(id, tenant, type, ((Number) claimed.get("attempts")).intValue()), payload);
            jdbc.update("update shared.processing_job set status = 'DONE', finished_at = now() where id = ?", id);
        });
    }

    private BigDecimal commissionOn(LocalDate day) {
        return commissionOn(day, creditorA);
    }

    private BigDecimal commissionOn(LocalDate day, UUID creditorId) {
        MarginReport report = asRole(manager, DefaultRoles.GESTOR, null,
                () -> margins.margin(new MarginFilter(day, day, null, null)));
        return cost(creditor(report, creditorId).line(), CostCategory.VARIABLE_COMMISSION);
    }

    private UUID attrB() {
        return attribution(creditorB, portfolioB);
    }

    private String jobStatus(LocalDate period) {
        return system(() -> jdbc.query("""
                select status || coalesce(' (' || attempts || '; ' || run_at || ' x ' || now() || '): ' || last_error, '')
                  from shared.processing_job where type = 'costing.commission_refresh' and dedup_key like ?
                 order by created_at desc limit 1""", rs -> rs.next() ? rs.getString(1) : null,
                "commission:" + period + ":%"));
    }

    private static void awaitTrue(String what, BooleanSupplier condition) {
        awaitTrue(what, condition, () -> "");
    }

    private static void awaitTrue(String what, BooleanSupplier condition, Supplier<String> diagnostics) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("tempo esgotado esperando: " + what + " " + diagnostics.get());
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private void seedTenantAndCreditors() {
        String slug = "simultaneo-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> provisioning.provision(
                new ProvisionTenantCommand(slug, "Assessoria Simultânea Ltda", "Simultânea", "11.444.777/0001-61",
                        "Admin", "admin@" + slug + ".test"))).tenant().id();
        Map<String, UUID> roleIds = system(() -> roles.list().stream()
                .collect(Collectors.toMap(RoleManagementUseCases.RoleView::code, RoleManagementUseCases.RoleView::id)));
        operatorX = user("Operador X", roleIds.get(DefaultRoles.OPERADOR));
        manager = user("Gestor G", roleIds.get(DefaultRoles.GESTOR));
        finance = user("Financeiro F", roleIds.get(DefaultRoles.FINANCEIRO));
        run(() -> {
            creditorA = creditor("Credor A");
            creditorB = creditor("Credor B");
            portfolioA = portfolio(creditorA, "PA");
            portfolioB = portfolio(creditorB, "PB");
        });
    }

    /** Mensagem de chat: do devedor (entrada) ou saída escrita pela pessoa {@code userId} (nula = assistente). */
    private void chat(UUID conversationId, UUID account, boolean outbound, UUID userId, Instant at) {
        jdbc.update("""
                insert into communication.message (id, tenant_id, version, created_at, updated_at, conversation_id,
                    channel, channel_account_id, direction, sender_type, sender_id, status, status_at, send_attempts,
                    queued_at, sent_at, received_at)
                values (?, ?, 0, ?, ?, ?, 'WHATSAPP', ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)""",
                Ids.newId(), tenant, ts(at), ts(at), conversationId, account, outbound ? "OUTBOUND" : "INBOUND",
                outbound ? (userId == null ? "ASSISTANT" : "USER") : "DEBTOR", userId, outbound ? "SENT" : "RECEIVED",
                ts(at), outbound ? ts(at) : null, outbound ? ts(at) : null, outbound ? null : ts(at));
    }

    private void configure() {
        asRole(finance, DefaultRoles.FINANCEIRO, null, () -> {
            settings.createBand(new CreateBandCommand("Negociador júnior", new BigDecimal("36.00"),
                    LocalDate.of(2026, 1, 1)));
            settings.assignUserBand(operatorX, new AssignUserBandCommand("Negociador júnior", LocalDate.of(2026, 1, 1)));
            settings.createTariff(new CreateTariffCommand(TariffChannel.WHATSAPP_UTILITY, new BigDecimal("0.04"), FROM));
            settings.createTariff(new CreateTariffCommand(TariffChannel.SMS, new BigDecimal("0.08"), FROM));
            settings.saveFxRate(new SaveFxRateCommand(YearMonth.of(2026, 10), new BigDecimal("5.10")));
            return settings.createOverhead(new CreateOverheadCommand(YearMonth.of(2026, 10), "Aluguel do escritório",
                    new BigDecimal("1000.00"), OverheadAllocation.HOURS, null));
        });
    }

    private void seed() {
        String slug = "custos-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> provisioning.provision(
                new ProvisionTenantCommand(slug, "Assessoria Custos Ltda", "Custos", "11.444.777/0001-61", "Admin",
                        "admin@" + slug + ".test"))).tenant().id();
        Map<String, UUID> roleIds = system(() -> roles.list().stream()
                .collect(Collectors.toMap(RoleManagementUseCases.RoleView::code, RoleManagementUseCases.RoleView::id)));
        operatorX = user("Operador X", roleIds.get(DefaultRoles.OPERADOR));
        operatorY = user("Operador Y", roleIds.get(DefaultRoles.OPERADOR));
        manager = user("Gestor G", roleIds.get(DefaultRoles.GESTOR));
        finance = user("Financeiro F", roleIds.get(DefaultRoles.FINANCEIRO));
        run(() -> {
            creditorA = creditor("Credor A");
            creditorB = creditor("Credor B");
            portfolioA = portfolio(creditorA, "PA");
            portfolioB = portfolio(creditorB, "PB");
            UUID debtor = debtor();
            UUID caseA = collectionCase(debtor, creditorA, portfolioA);
            UUID caseB = collectionCase(debtor, creditorB, portfolioB);
            UUID tabulation = tabulation();
            // dia 15: X 1 h em A e 0,5 h em B; Y 0,5 h em A; X logado 3 h
            interaction(caseA, debtor, creditorA, portfolioA, operatorX, at(15, 9), 3600, tabulation);
            interaction(caseB, debtor, creditorB, portfolioB, operatorX, at(15, 11), 1800, tabulation);
            interaction(caseA, debtor, creditorA, portfolioA, operatorY, at(15, 14), 1800, tabulation);
            // interação do assistente não é tempo de pessoa
            interaction(caseA, debtor, creditorA, portfolioA, null, at(15, 16), 900, tabulation);
            loggedTime(operatorX, LocalDate.of(2026, 10, 15), 3 * 3600);
            // dia 16: chamada de X em A com 61 s de conversa e 12 min de pós-atendimento (sem tempo logado)
            UUID account = providerAccount(new BigDecimal("0.10"));
            call(account, debtor, creditorA, portfolioA, caseA, operatorX, at(16, 10), 61, 720);
            // dia 17: Y logado 1 h sem nenhum trabalho direto
            loggedTime(operatorY, LocalDate.of(2026, 10, 17), 3600);
            // mensagens: 2 templates de utilidade na mesma conversa em 10 h; 1 SMS em B
            UUID whatsapp = channelAccount("WHATSAPP", creditorA);
            UUID template = template(creditorA);
            UUID conversation = conversation(debtor, creditorA, portfolioA, "WHATSAPP", whatsapp);
            message(conversation, "WHATSAPP", whatsapp, template, at(15, 8));
            message(conversation, "WHATSAPP", whatsapp, template, at(15, 18));
            UUID sms = channelAccount("SMS", creditorB);
            message(conversation(debtor, creditorB, portfolioB, "SMS", sms), "SMS", sms, null, at(15, 9));
            // recebimento de R$ 1.000 em A com comissão 150 e honorários da assessoria 50
            receiptWithCommission(creditorA, portfolioA, LocalDate.of(2026, 10, 15));
        });
    }

    private UUID user(String name, UUID roleId) {
        return system(() -> userManagement.create(new CreateUserCommand(name, UUID.randomUUID().toString()
                .substring(0, 8) + "@teste.com", null, Set.of(roleId), true, Set.of())).id());
    }

    private UUID attribution(UUID creditorId, UUID portfolioId) {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        jdbc.update("""
                insert into incentive.agreement_attribution (id, tenant_id, version, created_at, updated_at,
                    source_event_id, agreement_id, creditor_id, portfolio_id, origin, channel, delay_bucket,
                    agreement_amount, incremental_amount, formalized_at)
                values (?, ?, 0, ?, ?, ?, ?, ?, ?, 'HUMAN', 'PHONE', 'D31_90', 1000, 1000, ?)""",
                id, tenant, ts(now), ts(now), Ids.newId(), Ids.newId(), creditorId, portfolioId, ts(now));
        return id;
    }

    private UUID commissionPeriod(LocalDate start) {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        jdbc.update("""
                insert into incentive.commission_period (id, tenant_id, version, created_at, updated_at, period_start,
                    period_end, status, closed_at, close_trigger) values (?, ?, 0, ?, ?, ?, ?, 'CLOSED', ?, 'MANUAL')""",
                id, tenant, ts(now), ts(now), start, start.plusMonths(1).minusDays(1), ts(now));
        return id;
    }

    private UUID statement(UUID periodId, UUID beneficiary, LocalDate start, String payable, String credited) {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        jdbc.update("""
                insert into incentive.commission_statement (id, tenant_id, version, created_at, updated_at, period_id,
                    period_start, beneficiary_id, status, outcome, calculated_amount, adjustments_amount, payable_amount,
                    withheld_amount, credited_recovery, calculation, calculation_schema, calculated_at)
                values (?, ?, 0, ?, ?, ?, ?, ?, 'CALCULATED', 'PAYABLE', ?, 0, ?, 0, ?, '{}'::jsonb, 1, ?)""",
                id, tenant, ts(now), ts(now), periodId, start, beneficiary, new BigDecimal(payable),
                new BigDecimal(payable), new BigDecimal(credited), ts(now));
        return id;
    }

    private UUID ledger(UUID beneficiary, UUID attributionId, UUID creditorId, UUID portfolioId, String credited,
                        LocalDate paidOn, String status, UUID statementId) {
        return ledger(beneficiary, "USER", attributionId, creditorId, portfolioId, credited, paidOn, status, statementId);
    }

    private UUID ledger(UUID beneficiary, String beneficiaryType, UUID attributionId, UUID creditorId, UUID portfolioId,
                        String credited, LocalDate paidOn, String status, UUID statementId) {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        boolean cleared = status.equals("CLEARED");
        jdbc.update("""
                insert into incentive.commission_ledger_entry (id, tenant_id, version, created_at, updated_at, kind,
                    status, beneficiary_type, beneficiary_id, attribution_id, agreement_id, payment_id, creditor_id,
                    portfolio_id, delay_bucket, origin, channel, base_amount, credit_weight, renegotiation_factor,
                    credited_amount, credit_note, paid_on, confirmed_on, clears_on, cleared_at, period_start,
                    statement_id)
                values (?, ?, 0, ?, ?, 'ACCRUAL', ?, ?, ?, ?, ?, ?, ?, ?, 'D31_90', 'HUMAN', 'PHONE', ?, 100, 100,
                        ?, 'teste', ?, ?, ?, ?, ?, ?)""",
                id, tenant, ts(now), ts(now), status, beneficiaryType, beneficiary, attributionId, Ids.newId(), Ids.newId(),
                creditorId,
                portfolioId, new BigDecimal(credited), new BigDecimal(credited), paidOn, paidOn, paidOn,
                cleared ? ts(now) : null, cleared ? LocalDate.of(2026, 10, 1) : null, statementId);
        return id;
    }

    private void reversal(UUID beneficiary, UUID attributionId, UUID creditorId, UUID portfolioId, UUID reverses,
                          String credited, Instant at) {
        jdbc.update("""
                insert into incentive.commission_ledger_entry (id, tenant_id, version, created_at, updated_at, kind,
                    status, beneficiary_type, beneficiary_id, attribution_id, agreement_id, payment_id, reversal_id,
                    reverses_entry_id, creditor_id, portfolio_id, delay_bucket, origin, channel, base_amount,
                    credit_weight, renegotiation_factor, credited_amount, credit_note, paid_on, confirmed_on, clears_on)
                values (?, ?, 0, ?, ?, 'REVERSAL', 'PENDING', 'USER', ?, ?, ?, ?, ?, ?, ?, ?, 'D31_90', 'HUMAN',
                        'PHONE', ?, 100, 100, ?, 'teste', ?, ?, ?)""",
                Ids.newId(), tenant, ts(at), ts(at), beneficiary, attributionId, Ids.newId(), Ids.newId(), Ids.newId(),
                reverses, creditorId, portfolioId, new BigDecimal(credited), new BigDecimal(credited),
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5));
    }

    private <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    private void run(Runnable action) {
        tenantExecution.inTenant(tenant, "test", action);
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

    private static BigDecimal cost(MarginLine line, CostCategory category) {
        return line.costs().get(category);
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

    private UUID tabulation() {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        jdbc.update("""
                insert into collection.tabulation (id, tenant_id, version, created_at, updated_at, code, name,
                    effective_contact, promise, callback, requires_note, display_order, active, follow_up)
                values (?, ?, 0, ?, ?, 'CPC_CUSTOS', 'CPC', true, false, false, false, 99, true, 'NONE')""",
                id, tenant, ts(now), ts(now));
        return id;
    }

    private void interaction(UUID caseId, UUID debtorId, UUID creditorId, UUID portfolioId, UUID operatorId,
                             Instant start, int seconds, UUID tabulation) {
        jdbc.update("""
                insert into collection.interaction (id, tenant_id, version, created_at, updated_at, case_id, debtor_id,
                    creditor_id, portfolio_id, channel, direction, tabulation_id, outcome_code, effective_contact,
                    started_at, ended_at, duration_seconds, operator_id, actor_type, source, follow_up)
                values (?, ?, 0, ?, ?, ?, ?, ?, ?, 'PHONE', 'OUTBOUND', ?, 'CPC_CUSTOS', true, ?, ?, ?, ?, ?, 'MANUAL',
                        'NONE')""",
                Ids.newId(), tenant, ts(start), ts(start), caseId, debtorId, creditorId, portfolioId, tabulation,
                ts(start), ts(start.plusSeconds(seconds)), seconds, operatorId, operatorId == null ? "ASSISTANT" : "USER");
    }

    private void loggedTime(UUID userId, LocalDate day, long seconds) {
        jdbc.update("insert into incentive.logged_time_daily (tenant_id, fact_date, user_id, seconds) values (?, ?, ?, ?)",
                tenant, day, userId, seconds);
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
                      Instant at, int talk, int wrapUp) {
        jdbc.update("""
                insert into telephony.phone_call (id, tenant_id, version, created_at, updated_at, direction, origin,
                    status, result, provider_account_id, provider, creditor_id, portfolio_id, debtor_id, case_id,
                    operator_id, attempt_number, number_masked, queued_at, dial_started_at, answered_at, ended_at,
                    talk_seconds, wrap_up_seconds, abandoned, recording_requested, recording_announced,
                    interaction_state)
                values (?, ?, 0, ?, ?, 'OUTBOUND', 'CLICK_TO_CALL', 'COMPLETED', 'ANSWERED', ?, 'SANDBOX', ?, ?, ?, ?,
                        ?, 1, '(11) *****-4321', ?, ?, ?, ?, ?, ?, false, false, false, 'NONE')""",
                Ids.newId(), tenant, ts(at), ts(at), account, creditorId, portfolioId, debtorId, caseId, operatorId,
                ts(at), ts(at), ts(at), ts(at.plusSeconds(talk)), talk, wrapUp);
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

    private void receiptWithCommission(UUID creditorId, UUID portfolioId, LocalDate on) {
        UUID receipt = Ids.newId();
        Instant now = Instant.now();
        jdbc.update("""
                insert into settlement.receipt (id, tenant_id, version, created_at, updated_at, kind, allocation_id,
                    payment_id, creditor_id, portfolio_id, amount, honorarium_amount, days_past_due, payment_type,
                    received_by, renegotiation, paid_on, competence_date, detail, rating_status)
                values (?, ?, 0, ?, ?, 'PAYMENT', ?, ?, ?, ?, 1000, 50, 30, 'INSTALLMENTS', 'AGENCY', false, ?, ?,
                        '{}'::jsonb, 'PENDING_RULES')""",
                receipt, tenant, ts(now), ts(now), Ids.newId(), Ids.newId(), creditorId, portfolioId, on, on);
        UUID contract = jdbc.queryForObject("select contract_id from organization.portfolio where id = ?", UUID.class,
                portfolioId);
        UUID ruleSet = Ids.newId();
        jdbc.update("""
                insert into settlement.commission_rule_set (id, tenant_id, version, created_at, updated_at, creditor_id,
                    contract_id, rule_version, status, rules, rules_schema_version, rules_edited_by, rules_edited_at)
                values (?, ?, 0, ?, ?, ?, ?, 1, 'DRAFT', '{}'::jsonb, 1, ?, ?)""",
                ruleSet, tenant, ts(now), ts(now), creditorId, contract, finance, ts(now));
        jdbc.update("""
                insert into settlement.commission_entry (id, tenant_id, version, created_at, updated_at, kind,
                    receipt_id, creditor_id, rule_set_id, rule_version, rule_code, recovered_amount, agency_honorarium,
                    creditor_honorarium, commission_base, rate, commission_amount, fixed_fee, transfer_effect,
                    calculation)
                values (?, ?, 0, ?, ?, 'ACCRUAL', ?, ?, ?, 1, 'PADRAO', 1000, 50, 0, 1000, 15, 150, 0, 800,
                        '{}'::jsonb)""",
                Ids.newId(), tenant, ts(now), ts(now), receipt, creditorId, ruleSet);
    }
}
