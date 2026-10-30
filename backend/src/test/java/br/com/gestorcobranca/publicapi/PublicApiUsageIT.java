package br.com.gestorcobranca.publicapi;

import br.com.gestorcobranca.organization.application.port.in.ContractIntegrationPricingUseCases;
import br.com.gestorcobranca.organization.application.port.in.ContractIntegrationPricingUseCases.IntegrationPricingCommand;
import br.com.gestorcobranca.organization.application.port.in.ContractIntegrationPricingUseCases.IntegrationPricingView;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken.IssuedToken;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases.StatementView;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiUsageUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiUsageUseCases.UsageReport;
import br.com.gestorcobranca.publicapi.domain.service.BillingCycles;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Consumo e faturamento: medição por requisição (bytes reais, status, registros), gravação em lote por hora, trilha
 * de acesso por requisição, importação pela API com idempotência por token e atribuição assíncrona dos registros,
 * condições de cobrança no contrato do credor (versionadas, não retroativas) e extrato por ciclo reproduzível, com
 * cobrança só de 2xx/3xx e tráfego em MB inteiros.
 */
class PublicApiUsageIT extends PublicApiTestSupport {

    @Autowired
    PublicApiUsageUseCases usage;
    @Autowired
    PublicApiBillingUseCases billing;
    @Autowired
    ContractIntegrationPricingUseCases pricing;
    @Autowired
    ApplicationContext context;
    @Autowired
    List<JobHandler<?>> jobHandlers;

    /** Grava o acumulado em memória como o agendamento faria (o job é package-private no módulo). */
    void flushUsage() throws Exception {
        Object job = context.getBean(Class.forName("br.com.gestorcobranca.publicapi.application.service.UsageFlushJob"));
        Method flush = job.getClass().getDeclaredMethod("flush");
        flush.setAccessible(true);
        flush.invoke(job);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void runImportJobs() {
        for (int i = 0; i < 50; i++) {
            List<Map<String, Object>> claimed = jdbc.queryForList("""
                    update shared.processing_job set status = 'RUNNING', attempts = attempts + 1, started_at = now()
                    where id = (select id from shared.processing_job where tenant_id = ? and type like 'ingestion.%'
                                and status = 'PENDING' order by created_at limit 1 for update skip locked)
                    returning id, type, payload::text as payload, attempts""", tenant);
            if (claimed.isEmpty()) {
                return;
            }
            UUID id = (UUID) claimed.getFirst().get("id");
            String type = (String) claimed.getFirst().get("type");
            JobHandler handler = jobHandlers.stream().filter(h -> h.type().equals(type)).findFirst().orElseThrow();
            Object payload = json.fromJson((String) claimed.getFirst().get("payload"), handler.payloadType());
            tenantExecution.inTenant(tenant, "job:" + type, () -> {
                handler.handle(new JobContext(id, tenant, type, ((Number) claimed.getFirst().get("attempts")).intValue()), payload);
                jdbc.update("update shared.processing_job set status = 'DONE', finished_at = now() where id = ?", id);
            });
        }
    }

    @Test
    void requestsAreMeteredLoggedAndFlushedInBatchesPerHour() throws Exception {
        IssuedToken token = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        String requestId = UUID.randomUUID().toString();
        HttpResponse<String> ok = call("GET", "/public-api/v1/portfolios", token.token(), null, Map.of("X-Request-Id", requestId));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(get("/public-api/v1/portfolios/" + portfolioB, token.token()).statusCode()).isEqualTo(404);

        // nada é gravado no caminho da requisição
        assertThat(system(() -> jdbc.queryForObject("select count(*) from publicapi.usage_hourly", Long.class))).isZero();
        flushUsage();

        Map<String, Object> list = system(() -> jdbc.queryForMap("""
                select requests, client_errors, bytes_out from publicapi.usage_hourly
                 where token_id = ? and route = '/public-api/v1/portfolios'""", token.details().id()));
        assertThat(list.get("requests")).isEqualTo(1L);
        assertThat((Long) list.get("bytes_out")).isEqualTo(ok.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        Map<String, Object> missing = system(() -> jdbc.queryForMap("""
                select requests, client_errors from publicapi.usage_hourly
                 where token_id = ? and route = '/public-api/v1/portfolios/{id}'""", token.details().id()));
        assertThat(missing.get("client_errors")).isEqualTo(1L);
        // 4xx não é cobrável: o tráfego dele não entra no tráfego cobrável
        assertThat(system(() -> jdbc.queryForObject("""
                select billable_bytes_out from publicapi.usage_hourly
                 where token_id = ? and route = '/public-api/v1/portfolios/{id}'""", Long.class, token.details().id())))
                .isZero();
        assertThat(system(() -> jdbc.queryForObject("""
                select billable_bytes_out from publicapi.usage_hourly
                 where token_id = ? and route = '/public-api/v1/portfolios'""", Long.class, token.details().id())))
                .isEqualTo((Long) list.get("bytes_out"));

        Map<String, Object> access = system(() -> jdbc.queryForMap("""
                select actor_type, actor_id, route, details from shared.access_log
                 where tenant_id = ? and event_type = 'PUBLIC_API_REQUEST' and request_id = ?""", tenant, requestId));
        assertThat(access.get("actor_type")).isEqualTo("INTEGRATION");
        assertThat(access.get("actor_id")).isEqualTo(token.details().id());
        assertThat(access.get("details").toString()).contains("\"status\": \"200\"").contains(creditorA.toString())
                .contains(token.details().prefix());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(system(() -> jdbc.queryForObject(
                "select last_used_at from publicapi.integration_token where id = ?", Instant.class, token.details().id())))
                .isNotNull());

        LocalDate today = LocalDate.now(TimeConfig.BUSINESS_ZONE);
        UsageReport report = asManager(() -> usage.report(today.minusDays(1), today.plusDays(1), creditorA, null));
        assertThat(report.totals().requests()).isEqualTo(2);
        assertThat(report.byToken()).singleElement().satisfies(t -> assertThat(t.prefix()).isEqualTo(token.details().prefix()));
        assertThat(report.byRoute()).hasSize(2);
    }

    @Test
    void apiImportIsIdempotentPerTokenAndProcessedRecordsAreAttributedToTheToken() throws Exception {
        IssuedToken token = issue(creditorA, Set.of("imports:write", "imports:read", "titles:read"), null, List.of());
        String body = """
                {"portfolioId":"%s","records":[
                  {"documento":"529.982.247-25","nome":"Maria Silva","id_titulo":"API-1","vencimento":"2026-03-10","valor_principal":150.00},
                  {"documento":"111.444.777-35","nome":"João Souza","id_titulo":"API-2","vencimento":"2026-03-11","valor_principal":99.90}
                ]}""".formatted(portfolioA);
        HttpResponse<String> first = call("POST", "/public-api/v1/imports", token.token(), body,
                Map.of("Idempotency-Key", "erp-carga-000001"));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(202);
        HttpResponse<String> repeated = call("POST", "/public-api/v1/imports", token.token(), body,
                Map.of("Idempotency-Key", "erp-carga-000001"));
        assertThat(body(repeated).get("id")).isEqualTo(body(first).get("id"));
        assertThat(call("POST", "/public-api/v1/imports", token.token(), body, Map.of()).statusCode()).isEqualTo(422);
        // a mesma chave noutro token não colide (isolada por token)
        IssuedToken other = issue(creditorA, Set.of("imports:write"), null, List.of());
        HttpResponse<String> otherToken = call("POST", "/public-api/v1/imports", other.token(), body,
                Map.of("Idempotency-Key", "erp-carga-000001"));
        assertThat(otherToken.statusCode()).isIn(202, 409);
        // carteira de outro credor: inexistente
        assertThat(call("POST", "/public-api/v1/imports", token.token(), body.replace(portfolioA.toString(),
                portfolioB.toString()), Map.of("Idempotency-Key", "erp-carga-000002")).statusCode()).isEqualTo(404);

        runImportJobs();
        UUID batchId = UUID.fromString(body(first).get("id").toString());
        HttpResponse<String> batch = get("/public-api/v1/imports/" + batchId, token.token());
        assertThat(body(batch).get("status")).isEqualTo("COMPLETED");
        HttpResponse<String> title = get("/public-api/v1/titles/by-external-id/API-1", token.token());
        assertThat(title.statusCode()).isEqualTo(200);
        assertThat(title.headers().firstValue("ETag")).isPresent();
        HttpResponse<String> sync = get("/public-api/v1/titles?limit=1", token.token());
        assertThat(body(sync).get("hasMore")).isEqualTo(true);
        String cursor = body(sync).get("nextCursor").toString();
        HttpResponse<String> next = get("/public-api/v1/titles?limit=1&cursor=" + cursor, token.token());
        assertThat(next.body()).doesNotContain(((List<Map<String, Object>>) body(sync).get("data")).getFirst().get("id").toString());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            flushUsage();
            Long records = system(() -> jdbc.queryForObject("""
                    select coalesce(sum(records), 0) from publicapi.usage_hourly where token_id = ? and method = 'ASYNC'""",
                    Long.class, token.details().id()));
            assertThat(records).isEqualTo(2L);
        });
    }

    /** Condições de cobrança registradas diretamente (instante passado), como se definidas antes dos ciclos. */
    UUID insertPricing(UUID creditorId, int version, int closingDay, Instant registeredAt) {
        return insertPricing(creditorId, version, closingDay, registeredAt, new BigDecimal("50.00"));
    }

    UUID insertPricing(UUID creditorId, int version, int closingDay, Instant registeredAt, BigDecimal monthlyFee) {
        UUID contractId = system(() -> contracts.listByCreditor(creditorId)).getFirst().id();
        UUID id = UUID.randomUUID();
        system(() -> jdbc.update("""
                insert into organization.contract_integration_pricing (id, tenant_id, version, created_at, updated_at,
                    contract_id, creditor_id, pricing_version, usage_closing_day, monthly_fee, included_requests,
                    price_per_request, included_mb_in, price_per_mb_in, included_mb_out, price_per_mb_out,
                    included_records, price_per_record, registered_at)
                values (?, ?, 0, now(), now(), ?, ?, ?, ?, ?, 1000, 0.01, 1, 1.00, 0, 0.50, 500, 0.02, ?)""",
                id, tenant, contractId, creditorId, version, closingDay, monthlyFee, java.sql.Timestamp.from(registeredAt)));
        return id;
    }

    void insertUsage(UUID creditorId, UUID tokenId, Instant hour, String route, long requests, long throttled,
                     long clientErrors, long serverErrors, long billableBytesIn, long billableBytesOut, long records) {
        system(() -> jdbc.update("""
                insert into publicapi.usage_hourly (tenant_id, creditor_id, token_id, hour_start, method, route, requests,
                    throttled, client_errors, server_errors, bytes_in, bytes_out, billable_bytes_in, billable_bytes_out,
                    records, latency_ms_total, updated_at)
                values (?, ?, ?, date_trunc('hour', ?::timestamptz), 'GET', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1000, now())""",
                tenant, creditorId, tokenId, java.sql.Timestamp.from(hour), route, requests, throttled, clientErrors,
                serverErrors, billableBytesIn + 5_000, billableBytesOut + 7_000, billableBytesIn, billableBytesOut,
                records));
    }

    @Test
    void contractPricingIsVersionedAppendOnlyAndNotRetroactive() {
        UUID contractId = system(() -> contracts.listByCreditor(creditorA)).getFirst().id();
        IntegrationPricingCommand command = new IntegrationPricingCommand(31, new BigDecimal("50.00"), 1000,
                new BigDecimal("0.01"), 10, new BigDecimal("1"), 10, new BigDecimal("0.5"), 500, new BigDecimal("0.02"),
                null);
        Instant beforeAny = Instant.now();
        IntegrationPricingView v1 = asManager(() -> pricing.define(contractId, command));
        IntegrationPricingView v2 = asManager(() -> pricing.define(contractId, new IntegrationPricingCommand(10,
                new BigDecimal("60.00"), 1000, new BigDecimal("0.01"), 10, new BigDecimal("1"), 10,
                new BigDecimal("0.5"), 500, new BigDecimal("0.02"), "reajuste anual")));
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.monthlyFee()).isEqualByComparingTo("60.00");
        assertThat(asManager(() -> pricing.history(contractId))).extracting(IntegrationPricingView::version)
                .containsExactly(2, 1);
        // vale para ciclos que começam depois do registro: um ciclo iniciado antes continua sem as novas condições
        assertThat(asManager(() -> pricing.applicable(creditorA, beforeAny))).isEmpty();
        assertThat(asManager(() -> pricing.applicable(creditorA, v2.registeredAt().plusSeconds(1))))
                .hasValueSatisfying(p -> assertThat(p.version()).isEqualTo(2));
        assertThat(asManager(() -> pricing.applicable(creditorA, v2.registeredAt())))
                .hasValueSatisfying(p -> assertThat(p.version()).isEqualTo(1));
        // dia de fechamento entre 1 e 31; valores com no máximo 2 casas na mensalidade
        assertThatThrownBy(() -> asManager(() -> pricing.define(contractId, new IntegrationPricingCommand(32,
                BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, null))))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> asManager(() -> pricing.define(contractId, new IntegrationPricingCommand(5,
                new BigDecimal("1.001"), 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                null)))).isInstanceOf(BusinessRuleException.class);
        // só quem gerencia contratos define preços
        assertThatThrownBy(() -> asUser(UUID.randomUUID(), EnumSet.of(Permission.CREDITOR_VIEW), CreditorScope.ALL,
                () -> pricing.define(contractId, command))).isInstanceOf(ScopeAccessDeniedException.class);
        // versão registrada não muda nem é apagada (gatilho)
        assertThatThrownBy(() -> system(() -> jdbc.update(
                "update organization.contract_integration_pricing set monthly_fee = 0 where id = ?", v1.id())))
                .rootCause().hasMessageContaining("contract_integration_pricing");
    }

    @Test
    void statementUsesContractPricingAndBillsOnlySuccessfulTrafficInWholeMegabytes() {
        IssuedToken first = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        IssuedToken second = issue(creditorA, Set.of("titles:read"), null, List.of());
        LocalDate today = LocalDate.now(TimeConfig.BUSINESS_ZONE);
        LocalDate cycleStart = today.minusMonths(3).withDayOfMonth(11);
        LocalDate cycleEnd = cycleStart.plusMonths(1).withDayOfMonth(10);
        Instant startInstant = cycleStart.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        // condições v1 registradas antes do ciclo (dia 10); v2 registrada agora não vale para ele
        insertPricing(creditorA, 1, 10, startInstant.minus(java.time.Duration.ofDays(60)));
        // depois do ciclo do teste, condições sem mensalidade: ciclos seguintes sem consumo não geram extrato
        insertPricing(creditorA, 2, 10, cycleEnd.atTime(12, 0).atZone(TimeConfig.BUSINESS_ZONE).toInstant(),
                BigDecimal.ZERO);
        UUID contractId = system(() -> contracts.listByCreditor(creditorA)).getFirst().id();
        asManager(() -> pricing.define(contractId, new IntegrationPricingCommand(10, new BigDecimal("999.00"), 0,
                BigDecimal.ONE, 0, BigDecimal.ONE, 0, BigDecimal.ONE, 0, BigDecimal.ONE, null)));

        Instant inCycle = startInstant.plusSeconds(3 * 3600);
        // token 1: 1.500 requisições (100 × 429, 10 × 4xx, 50 × 5xx não cobradas); 1 byte recebido e 1.048.577 enviados
        insertUsage(creditorA, first.details().id(), inCycle, "/public-api/v1/titles", 1500, 100, 10, 50, 1, 1_048_577, 700);
        // token 2: 10 requisições; exatamente 1.048.576 bytes recebidos
        insertUsage(creditorA, second.details().id(), inCycle, "/public-api/v1/portfolios", 10, 0, 0, 0, 1_048_576, 0, 0);
        // fora do ciclo (último segundo antes do início): não entra
        insertUsage(creditorA, first.details().id(), startInstant.minusSeconds(3600), "/public-api/v1/titles", 7, 0, 0,
                0, 10, 10, 0);

        int closed = asManager(() -> billing.closeEndedCycles(creditorA));
        // o consumo da véspera fica no ciclo anterior (11 a 10), fechado antes; depois o ciclo do teste
        assertThat(closed).isEqualTo(2);
        StatementView statement = asManager(() -> billing.statements(creditorA, null,
                br.com.gestorcobranca.shared.paging.PageQuery.of(0, 50))).content().stream()
                .filter(s -> s.periodStart().equals(cycleStart)).findFirst().orElseThrow();
        assertThat(statement.periodEnd()).isEqualTo(cycleEnd);
        assertThat(statement.usageClosingDay()).isEqualTo(10);
        assertThat(statement.pricingVersion()).isEqualTo(1);
        assertThat(statement.requests()).isEqualTo(1510);
        assertThat(statement.billableRequests()).isEqualTo(1350);
        // MB por token e direção, arredondado para cima: 1 byte → 1 MB, 1.048.576 → 1 MB, 1.048.577 → 2 MB
        assertThat(statement.billableMbIn()).isEqualTo(2);
        assertThat(statement.billableMbOut()).isEqualTo(2);
        assertThat(statement.records()).isEqualTo(700);
        // 50,00 + 350 × 0,01 + (2 − 1) × 1,00 + 2 × 0,50 + 200 × 0,02
        assertThat(statement.totalAmount()).isEqualByComparingTo("59.50");
        assertThat(statement.lines()).extracting(PublicApiBillingUseCases.StatementLine::metric)
                .containsExactly("MONTHLY_FEE", "REQUESTS", "MB_IN", "MB_OUT", "RECORDS");
        assertThat(statement.breakdown()).filteredOn(b -> b.kind().equals("TOKEN")).hasSize(2)
                .anySatisfy(b -> assertThat(b.totals()).containsEntry("billableMbOut", 2L).containsEntry("billableMbIn", 1L));

        // reprodutível; novo fechamento do mesmo ciclo só com motivo, como nova revisão
        assertThatThrownBy(() -> asManager(() -> billing.close(creditorA, cycleStart, null)))
                .isInstanceOf(ConflictException.class);
        StatementView revision = asManager(() -> billing.close(creditorA, cycleStart, "conferência do credor"));
        assertThat(revision.revision()).isEqualTo(2);
        assertThat(revision.usageHash()).isEqualTo(statement.usageHash());
        assertThat(revision.totalAmount()).isEqualByComparingTo(statement.totalAmount());
        assertThat(revision.periodEnd()).isEqualTo(cycleEnd);
        // nada mais encerrado a fechar: o fechamento sem período é recusado
        assertThatThrownBy(() -> asManager(() -> billing.close(creditorA, null, null)))
                .isInstanceOf(BusinessRuleException.class);
        StatementView preview = asManager(() -> billing.preview(creditorA));
        assertThat(preview.periodStart()).isAfter(cycleEnd);
        // extrato gravado é imutável no banco
        assertThatThrownBy(() -> system(() -> jdbc.update("update publicapi.usage_statement set total_amount = 1 where id = ?",
                statement.id()))).rootCause().hasMessageContaining("usage_statement");
        // o consumo só soma (gatilho)
        assertThatThrownBy(() -> system(() -> jdbc.update("update publicapi.usage_hourly set requests = 1 where token_id = ?",
                first.details().id()))).rootCause().hasMessageContaining("somado");
    }

    @Test
    void day31ClosesOnLastDayOfFebruaryAndCycleTurnsAtMidnightInBusinessZone() throws Exception {
        IssuedToken token = issue(creditorB, Set.of("portfolios:read"), null, List.of());
        insertPricing(creditorB, 1, 31, Instant.parse("2026-01-15T12:00:00Z"));
        // a partir do ciclo de abril, sem mensalidade: ciclos sem consumo não geram extrato
        insertPricing(creditorB, 2, 31, Instant.parse("2026-03-15T12:00:00Z"), BigDecimal.ZERO);
        java.time.ZoneId zone = TimeConfig.BUSINESS_ZONE;
        insertUsage(creditorB, token.details().id(), LocalDate.of(2026, 2, 1).atTime(10, 0).atZone(zone).toInstant(),
                "/public-api/v1/portfolios", 1, 0, 0, 0, 0, 100, 0);
        // 23h do dia 28/02 (horário de Brasília) ainda é o ciclo de fevereiro
        insertUsage(creditorB, token.details().id(), LocalDate.of(2026, 2, 28).atTime(23, 0).atZone(zone).toInstant(),
                "/public-api/v1/portfolios", 3, 0, 0, 0, 0, 100, 0);
        // 00:00 de 01/03 já é o ciclo de março
        insertUsage(creditorB, token.details().id(), LocalDate.of(2026, 3, 1).atStartOfDay(zone).toInstant(),
                "/public-api/v1/portfolios", 5, 0, 0, 0, 0, 100, 0);

        // o job diário fecha, em ordem, todos os ciclos encerrados
        Object job = context.getBean(Class.forName("br.com.gestorcobranca.publicapi.application.service.StatementClosingJob"));
        Method closeAll = job.getClass().getDeclaredMethod("closeAll");
        closeAll.setAccessible(true);
        closeAll.invoke(job);

        List<StatementView> closed = asManager(() -> billing.statements(creditorB, null,
                br.com.gestorcobranca.shared.paging.PageQuery.of(0, 50))).content();
        assertThat(closed).hasSize(2);
        StatementView february = closed.stream().filter(s -> s.periodStart().equals(LocalDate.of(2026, 2, 1)))
                .findFirst().orElseThrow();
        assertThat(february.periodEnd()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(february.requests()).isEqualTo(4);
        assertThat(february.billableMbOut()).isEqualTo(1);
        StatementView march = closed.stream().filter(s -> s.periodStart().equals(LocalDate.of(2026, 3, 1)))
                .findFirst().orElseThrow();
        assertThat(march.periodEnd()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(march.requests()).isEqualTo(5);
        // mês do fechamento como filtro da listagem
        assertThat(asManager(() -> billing.statements(creditorB, YearMonth.of(2026, 2),
                br.com.gestorcobranca.shared.paging.PageQuery.of(0, 50))).content()).singleElement()
                .satisfies(s -> assertThat(s.periodEnd()).isEqualTo(LocalDate.of(2026, 2, 28)));
        // uma segunda execução não duplica
        closeAll.invoke(job);
        assertThat(asManager(() -> billing.statements(creditorB, null,
                br.com.gestorcobranca.shared.paging.PageQuery.of(0, 50))).content()).hasSize(2);
    }

    @Test
    void cycleWithoutUsageClosesOnlyWithMonthlyFeeAndTransitionCycleIsProrated() throws Exception {
        IssuedToken token = issue(creditorB, Set.of("portfolios:read"), null, List.of());
        java.time.ZoneId zone = TimeConfig.BUSINESS_ZONE;
        // v1: fecha no dia 31, mensalidade 50,00
        insertPricing(creditorB, 1, 31, Instant.parse("2026-01-15T12:00:00Z"), new BigDecimal("50.00"));
        // v2 (registrada em 05/04, vale a partir do ciclo de maio): fecha no dia 10, mensalidade 30,00
        insertPricing(creditorB, 2, 10, Instant.parse("2026-04-05T12:00:00Z"), new BigDecimal("30.00"));
        // v3 (registrada em 01/06, vale a partir do ciclo que começa em 11/06): sem mensalidade
        insertPricing(creditorB, 3, 10, Instant.parse("2026-06-01T12:00:00Z"), BigDecimal.ZERO);
        // único consumo: fevereiro
        insertUsage(creditorB, token.details().id(), LocalDate.of(2026, 2, 3).atTime(9, 0).atZone(zone).toInstant(),
                "/public-api/v1/portfolios", 2, 0, 0, 0, 0, 10, 0);

        int closed = asManager(() -> billing.closeEndedCycles(creditorB));
        List<StatementView> all = asManager(() -> billing.statements(creditorB, null,
                br.com.gestorcobranca.shared.paging.PageQuery.of(0, 50))).content();
        // fevereiro (consumo), março e abril (sem consumo, com mensalidade), 01–10/05 (transição) e 11/05–10/06;
        // a partir de 11/06 não há consumo nem mensalidade: nenhum extrato
        assertThat(closed).isEqualTo(5);
        assertThat(all).extracting(StatementView::periodStart).containsExactlyInAnyOrder(LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 11));

        StatementView march = all.stream().filter(s -> s.periodStart().equals(LocalDate.of(2026, 3, 1))).findFirst()
                .orElseThrow();
        assertThat(march.requests()).isZero();
        assertThat(march.totalAmount()).isEqualByComparingTo("50.00");
        assertThat(march.cycleDays()).isEqualTo(march.referenceCycleDays());

        StatementView transition = all.stream().filter(s -> s.periodStart().equals(LocalDate.of(2026, 5, 1)))
                .findFirst().orElseThrow();
        assertThat(transition.periodEnd()).isEqualTo(LocalDate.of(2026, 5, 10));
        assertThat(transition.usageClosingDay()).isEqualTo(10);
        assertThat(transition.pricingVersion()).isEqualTo(2);
        // 10 dias de um ciclo de referência de 30 (11/04 a 10/05): 30,00 × 10 ÷ 30 = 10,00; franquia 1.000 → 334
        assertThat(transition.cycleDays()).isEqualTo(10);
        assertThat(transition.referenceCycleDays()).isEqualTo(30);
        assertThat(transition.totalAmount()).isEqualByComparingTo("10.00");
        assertThat(transition.lines().get(1).included()).isEqualTo(334);

        StatementView regular = all.stream().filter(s -> s.periodStart().equals(LocalDate.of(2026, 5, 11)))
                .findFirst().orElseThrow();
        assertThat(regular.periodEnd()).isEqualTo(LocalDate.of(2026, 6, 10));
        assertThat(regular.totalAmount()).isEqualByComparingTo("30.00");
        // nova execução não gera nada (ciclos sem consumo e sem mensalidade continuam sem extrato)
        assertThat(asManager(() -> billing.closeEndedCycles(creditorB))).isZero();
    }

    @Test
    void billingPositionShowsTheCurrentCycleSoFarAndIsExposedToTheCreditorByToken() throws Exception {
        IssuedToken token = issue(creditorA, Set.of("portfolios:read", "billing:read", "settlement:read"), null, List.of());
        java.time.ZoneId zone = TimeConfig.BUSINESS_ZONE;
        LocalDate today = LocalDate.now(zone);
        insertPricing(creditorA, 1, 31, Instant.parse("2025-12-01T12:00:00Z"), new BigDecimal("50.00"));
        // ciclo atual (mês corrente): 1.200 chamadas cobráveis, 1 byte recebido; ciclo anterior: 100 chamadas
        insertUsage(creditorA, token.details().id(), today.atStartOfDay(zone).toInstant().plusSeconds(60),
                "/public-api/v1/titles", 1200, 0, 0, 0, 1, 0, 0);
        LocalDate previousDay = today.withDayOfMonth(1).minusDays(1);
        insertUsage(creditorA, token.details().id(), previousDay.atTime(10, 0).atZone(zone).toInstant(),
                "/public-api/v1/titles", 100, 0, 0, 0, 0, 0, 0);

        PublicApiBillingUseCases.BillingPositionView p = asManager(() -> billing.position(creditorA));
        assertThat(p.periodStart()).isEqualTo(today.withDayOfMonth(1));
        assertThat(p.periodEnd()).isEqualTo(today.withDayOfMonth(today.lengthOfMonth()));
        assertThat(p.daysElapsed()).isEqualTo(today.getDayOfMonth());
        assertThat(p.daysRemaining()).isEqualTo(today.lengthOfMonth() - today.getDayOfMonth());
        assertThat(p.billableRequests()).isEqualTo(1200);
        assertThat(p.billableMbIn()).isEqualTo(1);
        // 50,00 + (1.200 − 1.000) × 0,01 + (1 − 1) MB recebido
        assertThat(p.amountSoFar()).isEqualByComparingTo("52.00");
        assertThat(p.allowances()).filteredOn(a -> a.metric().equals("REQUESTS")).singleElement()
                .satisfies(a -> assertThat(a.percent()).isEqualByComparingTo("120.00"));
        assertThat(p.daily()).hasSize(today.getDayOfMonth());
        assertThat(p.daily().getLast().amount()).isEqualByComparingTo(p.amountSoFar());
        assertThat(p.previous().periodEnd()).isEqualTo(previousDay);
        assertThat(p.previousDaily()).hasSize(previousDay.lengthOfMonth());
        assertThat(p.previousDaily().getLast().billableRequests()).isEqualTo(100);
        assertThat(p.projection().method()).isEqualTo("DAILY_AVERAGE");
        assertThat(p.projection().billableRequests()).isGreaterThanOrEqualTo(1200);
        assertThat(p.updatedAt()).isNotNull();
        assertThat(p.flushIntervalSeconds()).isPositive();

        // exposta ao próprio credor pela API pública (escopo billing:read), sem dados de quem fechou
        HttpResponse<String> position = get("/public-api/v1/billing/position", token.token());
        assertThat(position.statusCode()).as(position.body()).isEqualTo(200);
        assertThat(body(position).get("billableRequests")).isEqualTo(1200);
        assertThat(position.body()).doesNotContain("closedBy").doesNotContain("creditorId");
        assertThat(get("/public-api/v1/billing/statements", token.token()).statusCode()).isEqualTo(200);
        HttpResponse<String> settlement = get("/public-api/v1/settlement/position", token.token());
        assertThat(settlement.statusCode()).as(settlement.body()).isEqualTo(200);
        assertThat(settlement.body()).doesNotContain("calculation").doesNotContain("openStatementId");
        assertThat(body(get("/public-api/v1/settlement/statements", token.token())).get("data")).isEqualTo(List.of());
        // sem o escopo: proibido
        IssuedToken other = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        assertThat(get("/public-api/v1/billing/position", other.token()).statusCode()).isEqualTo(403);
        assertThat(get("/public-api/v1/settlement/position", other.token()).statusCode()).isEqualTo(403);
    }

    // ------------------------------------------------------------ calendário do contrato (decisão 624)

    void contractPeriod(UUID creditorId, LocalDate start, LocalDate end) {
        UUID contractId = system(() -> contracts.listByCreditor(creditorId)).getFirst().id();
        system(() -> contracts.updateTerms(contractId,
                new ServiceContractUseCases.ContractTermsCommand(start, end, null, 10, null)));
    }

    void closeAllByJob() throws Exception {
        Object job = context.getBean(Class.forName("br.com.gestorcobranca.publicapi.application.service.StatementClosingJob"));
        Method closeAll = job.getClass().getDeclaredMethod("closeAll");
        closeAll.setAccessible(true);
        closeAll.invoke(job);
    }

    List<StatementView> statementsOf(UUID creditorId) {
        return asManager(() -> billing.statements(creditorId, null,
                br.com.gestorcobranca.shared.paging.PageQuery.of(0, 100))).content().stream()
                .sorted(java.util.Comparator.comparing(StatementView::periodStart)).toList();
    }

    static int days(LocalDate from, LocalDate to) {
        return (int) java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
    }

    static BigDecimal prorated(String fee, int days, int reference) {
        return new BigDecimal(fee).multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(reference), 2, java.math.RoundingMode.HALF_EVEN);
    }

    @Test
    void cyclesStartAtTheContractStartAndTheMonthlyFeeIsBilledWithoutAnyUseOfTheApi() throws Exception {
        java.time.ZoneId zone = TimeConfig.BUSINESS_ZONE;
        LocalDate today = LocalDate.now(zone);
        // contrato do credor B de 20/(m−3) a 20/(m−1), fechamento dia 10, mensalidade 100,00; credor sem token e sem
        // nenhum consumo: só as condições o tornam candidato ao fechamento automático
        LocalDate start = today.minusMonths(3).withDayOfMonth(20);
        LocalDate end = today.minusMonths(1).withDayOfMonth(20);
        contractPeriod(creditorB, start, end);
        insertPricing(creditorB, 1, 10, start.minusDays(10).atStartOfDay(zone).toInstant(), new BigDecimal("100.00"));
        // credor A: condições sem mensalidade e nenhum consumo — nenhum extrato
        insertPricing(creditorA, 1, 10, Instant.parse("2025-12-01T12:00:00Z"), BigDecimal.ZERO);

        closeAllByJob();

        List<StatementView> all = statementsOf(creditorB);
        assertThat(all).hasSize(3);
        // primeiro ciclo: do início do contrato até o primeiro dia 10, proporcional ao ciclo de referência 11–10
        StatementView first = all.get(0);
        LocalDate firstEnd = start.plusMonths(1).withDayOfMonth(10);
        int firstDays = days(start, firstEnd);
        int firstReference = days(start.withDayOfMonth(11), firstEnd);
        assertThat(first.periodStart()).isEqualTo(start);
        assertThat(first.periodEnd()).isEqualTo(firstEnd);
        assertThat(first.cycleDays()).isEqualTo(firstDays);
        assertThat(first.referenceCycleDays()).isEqualTo(firstReference);
        assertThat(first.requests()).isZero();
        assertThat(first.totalAmount()).isEqualByComparingTo(prorated("100.00", firstDays, firstReference));
        assertThat(first.lines().get(1).included()).isEqualTo(BigDecimal.valueOf(1000L * firstDays)
                .divide(BigDecimal.valueOf(firstReference), 0, java.math.RoundingMode.CEILING).longValueExact());
        // ciclo regular, mensalidade cheia sem nenhum uso
        StatementView regular = all.get(1);
        assertThat(regular.periodStart()).isEqualTo(firstEnd.plusDays(1));
        assertThat(regular.periodEnd()).isEqualTo(start.plusMonths(2).withDayOfMonth(10));
        assertThat(regular.cycleDays()).isEqualTo(regular.referenceCycleDays());
        assertThat(regular.totalAmount()).isEqualByComparingTo("100.00");
        // último ciclo: termina no fim da vigência, proporcional ao ciclo 11–10 que o contém
        StatementView last = all.get(2);
        int lastDays = days(end.withDayOfMonth(11), end);
        int lastReference = days(end.withDayOfMonth(11), end.plusMonths(1).withDayOfMonth(10));
        assertThat(last.periodStart()).isEqualTo(end.withDayOfMonth(11));
        assertThat(last.periodEnd()).isEqualTo(end);
        assertThat(last.cycleDays()).isEqualTo(lastDays);
        assertThat(last.referenceCycleDays()).isEqualTo(lastReference);
        assertThat(last.totalAmount()).isEqualByComparingTo(prorated("100.00", lastDays, lastReference));

        // sem mensalidade e sem consumo: nada; nova execução não duplica nem gera ciclos depois da vigência
        assertThat(statementsOf(creditorA)).isEmpty();
        closeAllByJob();
        assertThat(statementsOf(creditorB)).hasSize(3);
        // posição depois do fim da vigência: sem contrato com condições, valor zero
        PublicApiBillingUseCases.BillingPositionView p = asManager(() -> billing.position(creditorB));
        assertThat(p.cycleKind()).isEqualTo("NO_CONTRACT");
        assertThat(p.pricingVersion()).isNull();
        assertThat(p.amountSoFar()).isEqualByComparingTo("0.00");
        assertThat(p.periodStart()).isAfter(end);
    }

    @Test
    void automaticClosingNeverReachesCyclesEndedBeforeTheConditionsWereRegistered() throws Exception {
        java.time.ZoneId zone = TimeConfig.BUSINESS_ZONE;
        LocalDate today = LocalDate.now(zone);
        IssuedToken token = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        // contrato de dois anos atrás; consumo de um ano atrás, de antes de qualquer condição de cobrança
        contractPeriod(creditorA, today.minusYears(2).withDayOfMonth(1), null);
        insertUsage(creditorA, token.details().id(), today.minusYears(1).atTime(10, 0).atZone(zone).toInstant(),
                "/public-api/v1/portfolios", 50, 0, 0, 0, 0, 10, 0);
        // condições registradas agora (dia 5, mensalidade 80,00)
        UUID contractId = system(() -> contracts.listByCreditor(creditorA)).getFirst().id();
        asManager(() -> pricing.define(contractId, new IntegrationPricingCommand(5, new BigDecimal("80.00"), 1000,
                new BigDecimal("0.01"), 0, BigDecimal.ONE, 0, BigDecimal.ONE, 0, BigDecimal.ONE, null)));

        // nenhum dos ~24 ciclos anteriores ao registro é fechado (nem o do consumo antigo)
        closeAllByJob();
        assertThat(statementsOf(creditorA)).isEmpty();

        // posição: o ciclo do calendário do contrato (dia 5) que contém hoje; as condições, registradas depois do seu
        // início, valem só do próximo ciclo (não retroativas)
        BillingCycles.Cycle current = BillingCycles.containing(today, 5);
        PublicApiBillingUseCases.BillingPositionView p = asManager(() -> billing.position(creditorA));
        assertThat(p.periodStart()).isEqualTo(current.start());
        assertThat(p.periodEnd()).isEqualTo(current.end());
        assertThat(p.usageClosingDay()).isEqualTo(5);
        assertThat(p.cycleKind()).isEqualTo("REGULAR");
        assertThat(p.pricingVersion()).isNull();
        assertThat(p.amountSoFar()).isEqualByComparingTo("0.00");
    }

    @Test
    void positionOfTheFirstCycleRunsFromTheContractStartAndIsProrated() throws Exception {
        java.time.ZoneId zone = TimeConfig.BUSINESS_ZONE;
        LocalDate today = LocalDate.now(zone);
        IssuedToken token = issue(creditorA, Set.of("billing:read"), null, List.of());
        // contrato desde anteontem; fechamento daqui a 5 dias: primeiro ciclo de 8 dias
        LocalDate start = today.minusDays(2);
        LocalDate closing = today.plusDays(5);
        contractPeriod(creditorA, start, null);
        insertPricing(creditorA, 1, closing.getDayOfMonth(), start.minusDays(10).atStartOfDay(zone).toInstant(),
                new BigDecimal("100.00"));

        int reference = days(BillingCycles.containing(closing, closing.getDayOfMonth()).start(), closing);
        PublicApiBillingUseCases.BillingPositionView p = asManager(() -> billing.position(creditorA));
        assertThat(p.periodStart()).isEqualTo(start);
        assertThat(p.periodEnd()).isEqualTo(closing);
        assertThat(p.cycleKind()).isEqualTo("CONTRACT_START");
        assertThat(p.cycleDays()).isEqualTo(8);
        assertThat(p.referenceCycleDays()).isEqualTo(reference);
        assertThat(p.daysElapsed()).isEqualTo(3);
        assertThat(p.pricingVersion()).isEqualTo(1);
        // mensalidade proporcional, como o fechamento cobraria (sem consumo)
        assertThat(p.amountSoFar()).isEqualByComparingTo(prorated("100.00", 8, reference));
        // o mesmo pela API pública
        HttpResponse<String> response = get("/public-api/v1/billing/position", token.token());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(body(response).get("cycleKind")).isEqualTo("CONTRACT_START");
        assertThat(body(response).get("cycleDays")).isEqualTo(8);
        // nada a fechar: o ciclo está em curso
        closeAllByJob();
        assertThat(statementsOf(creditorA)).isEmpty();
    }
}
