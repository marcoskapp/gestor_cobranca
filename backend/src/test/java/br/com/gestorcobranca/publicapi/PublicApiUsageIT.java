package br.com.gestorcobranca.publicapi;

import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken.IssuedToken;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases.PricePlanCommand;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases.StatementView;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiUsageUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiUsageUseCases.UsageReport;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
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
 * tabela de preços versionada e extrato reproduzível.
 */
class PublicApiUsageIT extends PublicApiTestSupport {

    @Autowired
    PublicApiUsageUseCases usage;
    @Autowired
    PublicApiBillingUseCases billing;
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

    @Test
    void priceTableIsVersionedAndMonthlyStatementIsReproducible() throws Exception {
        IssuedToken token = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        YearMonth previous = YearMonth.now(TimeConfig.BUSINESS_ZONE).minusMonths(1);
        Instant inPrevious = previous.atDay(10).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        // consumo do mês anterior (gravado como o job faria)
        system(() -> jdbc.update("""
                insert into publicapi.usage_hourly (tenant_id, creditor_id, token_id, hour_start, method, route, requests,
                    throttled, client_errors, server_errors, bytes_in, bytes_out, records, latency_ms_total, updated_at)
                values (?, ?, ?, date_trunc('hour', ?::timestamptz), 'GET', '/public-api/v1/titles', 1500, 100, 10, 50,
                    5000000, 30000000, 700, 1000, now())""", tenant, creditorA, token.details().id(),
                java.sql.Timestamp.from(inPrevious)));
        PricePlanCommand plan = new PricePlanCommand("PADRAO", null, "Tabela padrão", YearMonth.now(TimeConfig.BUSINESS_ZONE)
                .atDay(1), new BigDecimal("50.00"), 1000, new BigDecimal("0.01"), 10, new BigDecimal("1"), 10,
                new BigDecimal("0.5"), 500, new BigDecimal("0.02"));
        var draft = asManager(() -> billing.createDraft(plan));
        assertThat(draft.version()).isEqualTo(1);
        // vigência retroativa é recusada na publicação; vigência no mês anterior só por rascunho editado
        var published = asManager(() -> billing.publish(draft.id()));
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThatThrownBy(() -> asManager(() -> billing.updateDraft(draft.id(), plan))).isInstanceOf(ConflictException.class);

        // o mês anterior não tem tabela vigente (começa no mês corrente): extrato sem valor, mas com o consumo
        StatementView closed = asManager(() -> billing.close(creditorA, previous, null));
        assertThat(closed.requests()).isEqualTo(1500);
        assertThat(closed.billableRequests()).isEqualTo(1350);
        assertThat(closed.records()).isEqualTo(700);
        assertThat(closed.totalAmount()).isEqualByComparingTo("0");
        assertThat(closed.breakdown()).extracting(PublicApiBillingUseCases.StatementBreakdown::kind).contains("TOKEN", "ROUTE");

        StatementView preview = asManager(() -> billing.preview(creditorA, previous));
        assertThat(preview.usageHash()).isEqualTo(closed.usageHash());
        assertThatThrownBy(() -> asManager(() -> billing.close(creditorA, previous, null)))
                .isInstanceOf(ConflictException.class);
        StatementView revision = asManager(() -> billing.close(creditorA, previous, "conferência do credor"));
        assertThat(revision.revision()).isEqualTo(2);
        assertThat(revision.usageHash()).isEqualTo(closed.usageHash());
        assertThatThrownBy(() -> asManager(() -> billing.close(creditorA, YearMonth.now(TimeConfig.BUSINESS_ZONE), null)))
                .hasMessageContaining("encerrados");
        // extrato gravado é imutável no banco
        assertThatThrownBy(() -> system(() -> jdbc.update("update publicapi.usage_statement set total_amount = 1 where id = ?",
                closed.id()))).rootCause().hasMessageContaining("usage_statement");
        // o consumo só soma (gatilho)
        assertThatThrownBy(() -> system(() -> jdbc.update("update publicapi.usage_hourly set requests = 1 where token_id = ?",
                token.details().id()))).rootCause().hasMessageContaining("somado");
    }
}
