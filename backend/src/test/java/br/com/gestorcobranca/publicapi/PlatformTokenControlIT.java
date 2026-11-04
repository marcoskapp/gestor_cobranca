package br.com.gestorcobranca.publicapi;

import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.publicapi.application.port.in.IntegrationTokenUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.IntegrationTokenUseCases.TokenView;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken.IssuedToken;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PositionView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.RejectionRow;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.TenantSummary;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.TrafficRow;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformTokenControlUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformTokenControlUseCases.BulkResult;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformTokenControlUseCases.PlatformTokenView;
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

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Controle dos tokens pela plataforma e tráfego das assessorias suspensas: a pausa recusa as chamadas na hora (403
 * {@code auth.token_paused}), não cobra e mede a tentativa; a retomada (manual ou agendada) volta a aceitar; a
 * assessoria vê "pausado pela plataforma", não retoma, mas rotaciona e revoga; revogação pela plataforma; pausa em
 * massa; permissões exclusivas da plataforma; isolamento entre assessorias; trilhas, eventos e aviso; assessoria
 * suspensa no consolidado e na posição com tráfego (inclusive recusado) e valores zerados, sem extrato.
 */
class PlatformTokenControlIT extends PublicApiTestSupport {

    @Autowired
    PlatformTokenControlUseCases control;
    @Autowired
    PlatformBillingUseCases platformBilling;
    @Autowired
    IntegrationTokenUseCases tokenUseCases;
    @Autowired
    ApplicationContext context;

    UUID platformTenant;
    UUID platformUser;

    @BeforeEach
    void platform() {
        platformTenant = tenantExecution.callInTenant(Ids.newId(), "test.setup",
                () -> tenantAdministration.ensurePlatformTenant("11.444.777/0001-61").tenant().id());
        platformUser = Ids.newId();
    }

    // ------------------------------------------------------------------ apoio

    <T> T asPlatform(Supplier<T> action) {
        return asPlatform(EnumSet.of(Permission.TENANT_MANAGE, Permission.PLATFORM_BILLING_VIEW,
                Permission.PLATFORM_BILLING_MANAGE, Permission.PLATFORM_API_TOKENS_MANAGE), action);
    }

    <T> T asPlatform(Set<Permission> permissions, Supplier<T> action) {
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, platformUser, platformTenant, Ids.newId(),
                "plataforma", permissions, CreditorScope.ALL), action);
    }

    <T> T in(UUID tenantId, Supplier<T> action) {
        return tenantExecution.callInTenant(tenantId, "test", action);
    }

    void flushUsage() throws Exception {
        Object job = context.getBean(Class.forName("br.com.gestorcobranca.publicapi.application.service.UsageFlushJob"));
        Method flush = job.getClass().getDeclaredMethod("flush");
        flush.setAccessible(true);
        flush.invoke(job);
    }

    int runResumeJob() throws Exception {
        Object job = context.getBean(Class.forName(
                "br.com.gestorcobranca.publicapi.application.service.platform.PlatformTokenResumeJob"));
        Method resumeAll = job.getClass().getDeclaredMethod("resumeAll");
        resumeAll.setAccessible(true);
        return (Integer) resumeAll.invoke(job);
    }

    long rejected(UUID tokenId, String reason) {
        return system(() -> jdbc.queryForObject("""
                select coalesce(sum(attempts), 0) from publicapi.usage_rejected_hourly where token_id = ? and reason = ?""",
                Long.class, tokenId, reason));
    }

    long authenticatedRequests(UUID tokenId) {
        return system(() -> jdbc.queryForObject(
                "select coalesce(sum(requests), 0) from publicapi.usage_hourly where token_id = ?", Long.class, tokenId));
    }

    void awaitNoticeConsumed(UUID tenantId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        long consumed = 0;
        while (System.currentTimeMillis() < deadline) {
            consumed = in(tenantId, () -> jdbc.queryForObject("""
                    select count(*) from shared.consumed_event where consumer = 'identity.platform-token-notice'
                       and tenant_id = ?""", Long.class, tenantId));
            if (consumed >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(consumed).as("avisos consumidos pelo identity").isGreaterThanOrEqualTo(expected);
    }

    // ------------------------------------------------------------------ cenários

    @Test
    void pauseRefusesCallsAtOnceIsNotBilledIsMeasuredAndResumeAcceptsAgain() throws Exception {
        IssuedToken issued = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        UUID tokenId = issued.details().id();
        assertThat(get("/public-api/v1/creditor", issued.token()).statusCode()).isEqualTo(200);

        assertThatThrownBy(() -> asPlatform(() -> control.pause(tenant, tokenId, " ", null)))
                .isInstanceOf(BusinessRuleException.class);
        PlatformTokenView paused = asPlatform(() -> control.pause(tenant, tokenId, "tráfego anômalo de madrugada", null));
        assertThat(paused.state()).isEqualTo("PAUSED");
        assertThat(paused.pausedBy()).isEqualTo(platformUser);

        // efeito imediato: a próxima chamada já é recusada, com erro próprio da API pública
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> r = get("/public-api/v1/creditor", issued.token());
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("problem+json"));
            assertThat(body(r)).containsEntry("code", "auth.token_paused").containsKey("requestId");
            assertThat(r.body()).doesNotContain("madrugada");
        }
        flushUsage();
        // medido como tentativa recusada, fora das chamadas autenticadas (base da cobrança)
        assertThat(rejected(tokenId, "paused")).isEqualTo(3);
        assertThat(authenticatedRequests(tokenId)).isEqualTo(1);
        PositionView position = asPlatform(() -> platformBilling.position(tenant));
        assertThat(position.billableRequests()).isEqualTo(1);
        assertThat(position.traffic().totals().rejectedRequests()).isEqualTo(3);
        assertThat(position.traffic().rejections()).extracting(RejectionRow::reason, RejectionRow::attempts)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("paused", 3L));
        assertThat(position.traffic().rows()).filteredOn(r -> r.kind().equals("TOKEN")).singleElement()
                .satisfies(r -> {
                    assertThat(r.tokenState()).isEqualTo("PAUSED");
                    assertThat(r.totals().rejectedRequests()).isEqualTo(3);
                    assertThat(r.totals().billableRequests()).isEqualTo(1);
                });
        assertThat(asPlatform(() -> control.tokens(tenant))).singleElement()
                .satisfies(t -> assertThat(t.cycle().rejectedRequests()).isEqualTo(3));
        assertThat(system(() -> jdbc.queryForObject("""
                select count(*) from shared.access_log where tenant_id = ? and event_type = 'INTEGRATION_TOKEN_REJECTED'
                   and details->>'reason' = 'paused'""", Long.class, tenant))).isEqualTo(3L);

        // a assessoria vê a pausa com o motivo, sem identificar o usuário da plataforma, e não a encerra
        TokenView seen = asManager(() -> tokenUseCases.get(tokenId));
        assertThat(seen.state()).isEqualTo("PAUSED");
        assertThat(seen.pauseReason()).isEqualTo("tráfego anômalo de madrugada");
        assertThat(seen.pausedAt()).isNotNull();
        assertThat(asManager(() -> tokenUseCases.search(null, "PAUSED", PageQuery.of(0, 10))).content())
                .extracting(TokenView::id).containsExactly(tokenId);
        assertThat(asManager(() -> tokenUseCases.search(null, "ACTIVE", PageQuery.of(0, 10))).content()).isEmpty();
        // rotacionar (segredo exposto) não encerra a pausa
        IssuedToken rotated = asManager(() -> tokenUseCases.rotate(tokenId, 0));
        assertThat(rotated.details().state()).isEqualTo("PAUSED");
        assertThat(get("/public-api/v1/creditor", rotated.token()).statusCode()).isEqualTo(403);
        // só a plataforma retoma: a assessoria, mesmo forjando a permissão, é recusada
        assertThatThrownBy(() -> asUser(managerId, EnumSet.of(Permission.PLATFORM_API_TOKENS_MANAGE,
                Permission.PUBLIC_API_TOKEN_MANAGE), CreditorScope.ALL,
                () -> control.resume(tenant, tokenId, "quero retomar"))).isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> tenantExecution.callAs(new Actor(Actor.ActorType.USER, managerId, tenant, Ids.newId(),
                "forjado", EnumSet.of(Permission.PLATFORM_API_TOKENS_MANAGE), CreditorScope.ALL),
                () -> control.resume(tenant, tokenId, "quero retomar"))).isInstanceOf(ScopeAccessDeniedException.class);

        PlatformTokenView resumed = asPlatform(() -> control.resume(tenant, tokenId, "credor corrigiu o laço"));
        assertThat(resumed.state()).isEqualTo("ACTIVE");
        assertThat(get("/public-api/v1/creditor", rotated.token()).statusCode()).isEqualTo(200);
        assertThatThrownBy(() -> asPlatform(() -> control.resume(tenant, tokenId, "de novo")))
                .isInstanceOf(ConflictException.class);

        // trilhas: assessoria (com o usuário da plataforma) e plataforma; log de acesso e de negócio; aviso
        assertThat(system(() -> jdbc.queryForList("""
                select action from shared.audit_event where tenant_id = ? and entity_id = ? order by seq""",
                String.class, tenant, tokenId))).containsExactly("integration_token.created", "integration_token.paused",
                "integration_token.rotated", "integration_token.resumed");
        assertThat(system(() -> jdbc.queryForObject("""
                select metadata->>'requestedByPlatformUserId' from shared.audit_event
                 where tenant_id = ? and entity_id = ? and action = 'integration_token.paused'""",
                String.class, tenant, tokenId))).isEqualTo(platformUser.toString());
        assertThat(in(platformTenant, () -> jdbc.queryForList("""
                select action from shared.audit_event where entity_type = 'tenant' and entity_id = ?
                   and action like 'platform_api_token.%' order by seq""", String.class, tenant)))
                .containsExactly("platform_api_token.paused", "platform_api_token.resumed");
        assertThat(in(platformTenant, () -> jdbc.queryForList("""
                select event_type from shared.access_log where details->>'targetTenantId' = ?
                   and event_type in ('INTEGRATION_TOKEN_PAUSED', 'INTEGRATION_TOKEN_RESUMED') order by occurred_at""",
                String.class, tenant.toString()))).containsExactly("INTEGRATION_TOKEN_PAUSED", "INTEGRATION_TOKEN_RESUMED");
        assertThat(in(platformTenant, () -> jdbc.queryForObject("""
                select details->>'reason' from shared.access_log where details->>'targetTenantId' = ?
                   and event_type = 'INTEGRATION_TOKEN_PAUSED'""", String.class, tenant.toString())))
                .isEqualTo("tráfego anômalo de madrugada");
        assertThat(system(() -> jdbc.queryForList("""
                select event_type from shared.business_event where event_type in ('public_api.token_paused',
                   'public_api.token_resumed') order by occurred_at""", String.class)))
                .containsExactly("public_api.token_paused", "public_api.token_resumed");
        awaitNoticeConsumed(tenant, 2);
    }

    @Test
    void scheduledResumeAcceptsAtTheInstantAndIsRecordedByTheJob() throws Exception {
        IssuedToken issued = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        UUID tokenId = issued.details().id();
        Instant resumeAt = Instant.now().plus(Duration.ofHours(1));
        PlatformTokenView paused = asPlatform(() -> control.pause(tenant, tokenId, "pausa até a correção", resumeAt));
        assertThat(paused.resumeAt()).isNotNull();
        assertThat(asManager(() -> tokenUseCases.get(tokenId)).resumeAt()).isNotNull();
        assertThat(get("/public-api/v1/creditor", issued.token()).statusCode()).isEqualTo(403);
        assertThatThrownBy(() -> asPlatform(() -> control.pause(tenant, tokenId, "x",
                Instant.now().plus(Duration.ofDays(120))))).isInstanceOf(ConflictException.class);

        // a hora agendada chegou (simulada no banco): aceito já, antes de o job gravar
        system(() -> jdbc.update("""
                update publicapi.integration_token set paused_at = now() - interval '2 hours',
                       resume_at = now() - interval '1 minute' where id = ?""", tokenId));
        assertThat(get("/public-api/v1/creditor", issued.token()).statusCode()).isEqualTo(200);
        assertThat(asManager(() -> tokenUseCases.get(tokenId)).state()).isEqualTo("ACTIVE");

        assertThat(runResumeJob()).isGreaterThanOrEqualTo(1);
        assertThat(system(() -> jdbc.queryForObject("select status from publicapi.integration_token where id = ?",
                String.class, tokenId))).isEqualTo("ACTIVE");
        assertThat(system(() -> jdbc.queryForObject("""
                select metadata->>'trigger' from shared.audit_event where tenant_id = ? and entity_id = ?
                   and action = 'integration_token.resumed'""", String.class, tenant, tokenId))).isEqualTo("SCHEDULED");
        assertThat(runResumeJob()).isZero();
        awaitNoticeConsumed(tenant, 2);
    }

    @Test
    void platformRevokesAndPausesAllWhileTheTenantKeepsRevokingItsOwnTokens() throws Exception {
        IssuedToken a = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        IssuedToken b = issue(creditorB, Set.of("portfolios:read"), null, List.of());
        IssuedToken c = issue(creditorA, Set.of("portfolios:read"), null, List.of());

        PlatformTokenView revoked = asPlatform(() -> control.revoke(tenant, c.details().id(), "chave publicada no git"));
        assertThat(revoked.state()).isEqualTo("REVOKED");
        assertThat(revoked.revokedByPlatform()).isTrue();
        assertThat(get("/public-api/v1/creditor", c.token()).statusCode()).isEqualTo(401);
        TokenView seenRevoked = asManager(() -> tokenUseCases.get(c.details().id()));
        assertThat(seenRevoked.revokedByPlatform()).isTrue();
        assertThat(seenRevoked.revokedBy()).isNull();
        assertThat(seenRevoked.revokeReason()).isEqualTo("chave publicada no git");
        assertThatThrownBy(() -> asPlatform(() -> control.revoke(tenant, c.details().id(), "de novo")))
                .isInstanceOf(ConflictException.class);

        BulkResult all = asPlatform(() -> control.pauseAll(tenant, "consumo fora do contrato", null));
        assertThat(all.affected()).isEqualTo(2);
        assertThat(all.skipped()).isEqualTo(1);
        assertThat(get("/public-api/v1/creditor", a.token()).statusCode()).isEqualTo(403);
        assertThat(get("/public-api/v1/creditor", b.token()).statusCode()).isEqualTo(403);

        // a assessoria continua revogando o próprio token pausado (a revogação encerra a pausa)
        asManager(() -> tokenUseCases.revoke(b.details().id(), "integração desligada"));
        assertThat(get("/public-api/v1/creditor", b.token()).statusCode()).isEqualTo(401);

        BulkResult back = asPlatform(() -> control.resumeAll(tenant, "contrato regularizado"));
        assertThat(back.affected()).isEqualTo(1);
        assertThat(get("/public-api/v1/creditor", a.token()).statusCode()).isEqualTo(200);
        assertThat(back.tokens()).extracting(PlatformTokenView::state)
                .containsExactlyInAnyOrder("ACTIVE", "REVOKED", "REVOKED");
        assertThat(in(platformTenant, () -> jdbc.queryForList("""
                select action from shared.audit_event where entity_type = 'tenant' and entity_id = ?
                   and action like 'platform_api_token.%' order by seq""", String.class, tenant)))
                .containsExactly("platform_api_token.revoked", "platform_api_token.paused_all",
                        "platform_api_token.resumed_all");
        assertThat(system(() -> jdbc.queryForObject("""
                select count(*) from shared.audit_event where tenant_id = ? and action = 'integration_token.paused'""",
                Long.class, tenant))).isEqualTo(2L);
        awaitNoticeConsumed(tenant, 3);
    }

    @Test
    void onlyThePlatformAdminControlsTokensAndEachTenantIsIsolated() throws Exception {
        IssuedToken a = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        String slug = "pt-" + UUID.randomUUID().toString().substring(0, 8);
        UUID other = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration.createTenant(
                new CreateTenantCommand(slug, "Outra Ltda", "Outra", "11.444.777/0001-61")).id());
        in(other, () -> creditors.create(new CreditorCommand("Banco C S.A.", "Banco C", "11.222.333/0001-81", null,
                null)).id());

        // token de uma assessoria pedido pela outra: inexistente (RLS)
        assertThatThrownBy(() -> asPlatform(() -> control.pause(other, a.details().id(), "x", null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(asPlatform(() -> control.tokens(other))).isEmpty();
        // o tenant da plataforma não é alvo
        assertThatThrownBy(() -> asPlatform(() -> control.tokens(platformTenant)))
                .isInstanceOf(ResourceNotFoundException.class);
        // consulta sem a permissão de controle: lista, não pausa
        Set<Permission> viewOnly = EnumSet.of(Permission.PLATFORM_BILLING_VIEW);
        assertThat(asPlatform(viewOnly, () -> control.tokens(tenant))).hasSize(1);
        assertThatThrownBy(() -> asPlatform(viewOnly, () -> control.pause(tenant, a.details().id(), "x", null)))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asPlatform(EnumSet.of(Permission.PLATFORM_BILLING_MANAGE),
                () -> control.pauseAll(tenant, "x", null))).isInstanceOf(ScopeAccessDeniedException.class);
        // a assessoria não usa o controle da plataforma (permissão exclusiva + tenant da plataforma)
        assertThatThrownBy(() -> asManager(() -> control.tokens(tenant))).isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(get("/public-api/v1/creditor", a.token()).statusCode()).isEqualTo(200);
    }

    @Test
    void suspendedTenantShowsTrafficIncludingRefusedCallsWithZeroAmountsAndNoStatement() throws Exception {
        LocalDate today = LocalDate.now(TimeConfig.BUSINESS_ZONE);
        UUID policy = UUID.randomUUID();
        system(() -> {
            jdbc.update("""
                    insert into publicapi.platform_billing_policy (id, tenant_id, version, created_at, updated_at,
                        start_date) values (?, ?, 0, now(), now(), ?)""", policy, tenant, today.minusMonths(2).withDayOfMonth(1));
            return jdbc.update("""
                    insert into publicapi.platform_billing_policy_version (id, tenant_id, version, created_at,
                        updated_at, policy_id, policy_version, closing_day, monthly_fee, included_requests,
                        price_per_request, request_tiers, included_mb_in, price_per_mb_in, included_mb_out,
                        price_per_mb_out, included_records, price_per_record, registered_at)
                    values (?, ?, 0, now(), now(), ?, 1, 31, 300.00, 0, 0.10, '[]'::jsonb, 0, 1.00, 0, 0.50, 0, 0.02, ?)""",
                    UUID.randomUUID(), tenant, policy,
                    Timestamp.from(today.minusMonths(3).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant()));
        });
        IssuedToken a = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        assertThat(get("/public-api/v1/creditor", a.token()).statusCode()).isEqualTo(200);

        asPlatform(() -> tenantAdministration.changeTenantStatus(tenant, false, "inadimplência contratual"));
        for (int i = 0; i < 2; i++) {
            assertThat(get("/public-api/v1/creditor", a.token()).statusCode()).isEqualTo(401);
        }
        flushUsage();
        assertThat(rejected(a.details().id(), "tenant_inactive")).isEqualTo(2);

        TenantSummary summary = asPlatform(platformBilling::overview).tenants().stream()
                .filter(t -> t.tenantId().equals(tenant)).findFirst().orElseThrow();
        assertThat(summary.suspended()).isTrue();
        assertThat(summary.hasPolicy()).isTrue();
        assertThat(summary.amountSoFar()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.projectedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.billableRequests()).isEqualTo(1);
        assertThat(summary.rejectedRequests()).isEqualTo(2);
        assertThat(asPlatform(platformBilling::overview).totals().suspended()).isGreaterThanOrEqualTo(1);

        PositionView p = asPlatform(() -> platformBilling.position(tenant));
        assertThat(p.suspended()).isTrue();
        assertThat(p.amountSoFar()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(p.lines()).isEmpty();
        assertThat(p.allowances()).isEmpty();
        assertThat(p.daily()).allSatisfy(d -> assertThat(d.amount()).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(p.traffic().totals().billableRequests()).isEqualTo(1);
        assertThat(p.traffic().rows()).filteredOn(r -> r.kind().equals("CREDITOR")).extracting(TrafficRow::label)
                .containsExactly("Banco A");
        assertThat(p.traffic().rejections()).extracting(RejectionRow::reason).containsExactly("tenant_inactive");

        // sem extrato enquanto suspensa (nem prévia); a lista de tokens e a pausa continuam disponíveis
        assertThatThrownBy(() -> asPlatform(() -> platformBilling.preview(tenant)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("suspensa");
        assertThatThrownBy(() -> asPlatform(() -> platformBilling.close(tenant, null, null)))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(asPlatform(() -> control.pause(tenant, a.details().id(), "bloqueio preventivo", null)).state())
                .isEqualTo("PAUSED");
        assertThat(get("/public-api/v1/creditor", a.token()).statusCode()).isEqualTo(403);
        Map<String, Object> problem = body(get("/public-api/v1/creditor", a.token()));
        assertThat(problem).containsEntry("code", "auth.token_paused");
    }
}
