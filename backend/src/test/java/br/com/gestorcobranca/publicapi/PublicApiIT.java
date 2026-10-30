package br.com.gestorcobranca.publicapi;

import br.com.gestorcobranca.publicapi.application.port.in.IntegrationTokenUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken.IssueTokenCommand;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken.IssuedToken;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * API pública contra PostgreSQL real e HTTP real: ciclo de vida do token (segredo uma vez, só hash, revogação
 * imediata, rotação com carência), cadeia de segurança separada, isolamento por credor, escopos, limite de taxa,
 * erros RFC 9457, versões, ETag/If-Match e trilha de acesso e auditoria.
 */
class PublicApiIT extends PublicApiTestSupport {

    @Autowired
    IntegrationTokenUseCases tokens;

    @Test
    void secretIsShownOnceAndOnlyItsHmacIsStored() throws Exception {
        IssuedToken issued = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        assertThat(issued.token()).startsWith("gck_").contains(".");
        String secret = issued.token().substring(issued.token().indexOf('.') + 1);
        Map<String, Object> row = system(() -> jdbc.queryForMap(
                "select prefix, secret_hash, hash_key_id from publicapi.integration_token where id = ?", issued.details().id()));
        assertThat(row.get("secret_hash").toString()).hasSize(64).doesNotContain(secret);
        assertThat(issued.details().prefix()).isEqualTo("gck_" + row.get("prefix"));
        // a consulta posterior nunca devolve o segredo
        assertThat(asManager(() -> tokens.get(issued.details().id())).toString()).doesNotContain(secret);

        HttpResponse<String> ok = get("/public-api/v1/creditor", issued.token());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(body(ok).get("id")).isEqualTo(creditorA.toString());
        assertThat(ok.headers().firstValue("RateLimit-Limit")).contains("600");

        long audited = system(() -> jdbc.queryForObject("""
                select count(*) from shared.audit_event where tenant_id = ? and action = 'integration_token.created'
                   and entity_id = ?""", Long.class, tenant, issued.details().id()));
        assertThat(audited).isEqualTo(1);
    }

    @Test
    void invalidTokensAndUserJwtAreRejectedWithProblemDetails() throws Exception {
        IssuedToken issued = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        String wrongSecret = issued.token().substring(0, issued.token().indexOf('.') + 1) + "A".repeat(43);
        for (String token : new String[]{null, "nao-e-token", wrongSecret, "gck_aaaaaaaaaaaa." + "B".repeat(43)}) {
            HttpResponse<String> r = get("/public-api/v1/creditor", token);
            assertThat(r.statusCode()).isEqualTo(401);
            assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("problem+json"));
            assertThat(body(r)).containsEntry("code", "auth.unauthenticated").containsKey("requestId");
        }
        long rejected = system(() -> jdbc.queryForObject("""
                select count(*) from shared.access_log where tenant_id = ? and event_type = 'INTEGRATION_TOKEN_REJECTED'
                   and details->>'reason' = 'secret_mismatch'""", Long.class, tenant));
        assertThat(rejected).isEqualTo(1);
        // a API interna não aceita o token de integração (cadeias separadas)
        assertThat(get("/api/v1/creditors", issued.token()).statusCode()).isEqualTo(401);
    }

    @Test
    void revocationIsImmediateAndRotationKeepsPreviousSecretDuringGrace() throws Exception {
        IssuedToken issued = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        IssuedToken rotated = asManager(() -> tokens.rotate(issued.details().id(), 10));
        assertThat(rotated.token()).isNotEqualTo(issued.token());
        assertThat(rotated.details().prefix()).isEqualTo(issued.details().prefix());
        assertThat(get("/public-api/v1/creditor", rotated.token()).statusCode()).isEqualTo(200);
        assertThat(get("/public-api/v1/creditor", issued.token()).statusCode()).isEqualTo(200);

        IssuedToken again = asManager(() -> tokens.rotate(issued.details().id(), 0));
        assertThat(get("/public-api/v1/creditor", issued.token()).statusCode()).isEqualTo(401);
        assertThat(get("/public-api/v1/creditor", rotated.token()).statusCode()).isEqualTo(401);

        asManager(() -> tokens.revoke(issued.details().id(), "integração desligada pelo credor"));
        assertThat(get("/public-api/v1/creditor", again.token()).statusCode()).isEqualTo(401);
        assertThat(asManager(() -> tokens.get(issued.details().id())).state()).isEqualTo("REVOKED");
        List<String> actions = system(() -> jdbc.queryForList("""
                select action from shared.audit_event where tenant_id = ? and entity_id = ? order by occurred_at""",
                String.class, tenant, issued.details().id()));
        assertThat(actions).containsExactly("integration_token.created", "integration_token.rotated",
                "integration_token.rotated", "integration_token.revoked");
        // revogado não volta (gatilho no banco)
        assertThatThrownBy(() -> system(() -> jdbc.update(
                "update publicapi.integration_token set status = 'ACTIVE' where id = ?", issued.details().id())))
                .rootCause().hasMessageContaining("revogado");
    }

    @Test
    void tokenOnlySeesItsOwnCreditorAndRespectsScopes() throws Exception {
        IssuedToken tokenA = issue(creditorA, Set.of("portfolios:read"), null, List.of());
        HttpResponse<String> own = get("/public-api/v1/portfolios/" + portfolioA, tokenA.token());
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(own.headers().firstValue("ETag")).isPresent();
        // carteira de outro credor da mesma assessoria: inexistente para o token
        assertThat(get("/public-api/v1/portfolios/" + portfolioB, tokenA.token()).statusCode()).isEqualTo(404);
        HttpResponse<String> list = get("/public-api/v1/portfolios", tokenA.token());
        assertThat(list.body()).contains(portfolioA.toString()).doesNotContain(portfolioB.toString());
        // sem o escopo de escrita
        HttpResponse<String> denied = call("POST", "/public-api/v1/portfolios/" + portfolioA + "/status", tokenA.token(),
                "{\"status\":\"SUSPENDED\",\"reason\":\"x\"}", Map.of());
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(body(denied)).containsEntry("code", "access.denied");
    }

    @Test
    void ifMatchProtectsAgainstConcurrentChanges() throws Exception {
        IssuedToken token = issue(creditorA, Set.of("portfolios:read", "portfolios:write"), null, List.of());
        String etag = get("/public-api/v1/portfolios/" + portfolioA, token.token()).headers().firstValue("ETag").orElseThrow();
        String update = "{\"name\":\"Carteira renomeada\",\"receivedOn\":\"2026-01-01\"}";
        HttpResponse<String> first = call("PUT", "/public-api/v1/portfolios/" + portfolioA, token.token(), update,
                Map.of("If-Match", etag));
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("ETag")).isPresent().isNotEqualTo(java.util.Optional.of(etag));
        HttpResponse<String> stale = call("PUT", "/public-api/v1/portfolios/" + portfolioA, token.token(), update,
                Map.of("If-Match", etag));
        assertThat(stale.statusCode()).isEqualTo(412);
        assertThat(body(stale)).containsEntry("code", "portfolio.version_mismatch");
    }

    @Test
    void rateLimitAndIpAllowListAreEnforcedPerToken() throws Exception {
        IssuedToken limited = issue(creditorA, Set.of("portfolios:read"), 3, List.of());
        long epoch = System.currentTimeMillis() / 1000;
        if (epoch % 60 > 55) {
            Thread.sleep((61 - epoch % 60) * 1000);
        }
        int ok = 0;
        HttpResponse<String> last = null;
        for (int i = 0; i < 5; i++) {
            last = get("/public-api/v1/creditor", limited.token());
            ok += last.statusCode() == 200 ? 1 : 0;
        }
        assertThat(ok).isEqualTo(3);
        assertThat(last.statusCode()).isEqualTo(429);
        assertThat(last.headers().firstValue("Retry-After")).isPresent();
        assertThat(body(last)).containsEntry("code", "rate_limited");

        IssuedToken restricted = issue(creditorA, Set.of("portfolios:read"), null, List.of("203.0.113.0/24"));
        assertThat(get("/public-api/v1/creditor", restricted.token()).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/public-api/v1/creditor", restricted.token(), null,
                Map.of("X-Forwarded-For", "203.0.113.9")).statusCode()).isEqualTo(200);
    }

    @Test
    void userCannotGrantScopesBeyondOwnPermissionsNorManageOtherCreditors() {
        UUID integrador = UUID.randomUUID();
        Set<Permission> integracao = EnumSet.of(Permission.PUBLIC_API_TOKEN_MANAGE, Permission.PUBLIC_API_TOKEN_VIEW,
                Permission.IMPORT_VIEW, Permission.IMPORT_EXECUTE, Permission.CREDITOR_VIEW, Permission.PORTFOLIO_VIEW);
        assertThatThrownBy(() -> asUser(integrador, integracao, CreditorScope.ALL, () -> issuer.issue(new IssueTokenCommand(
                creditorA, "x", null, Set.of("debtors:read"), null, List.of(), null))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("DEBTOR_VIEW");
        assertThatThrownBy(() -> asUser(integrador, integracao, CreditorScope.of(List.of(creditorB)),
                () -> issuer.issue(new IssueTokenCommand(creditorA, "x", null, Set.of("imports:write"), null, List.of(), null))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        IssuedToken ofA = issue(creditorA, Set.of("imports:read"), null, List.of());
        assertThatThrownBy(() -> asUser(integrador, integracao, CreditorScope.of(List.of(creditorB)),
                () -> tokens.get(ofA.details().id()))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void versionsDocumentationAndUnknownRoutesArePublicOrProblemDetails() throws Exception {
        assertThat(get("/public-api/versions", null).body()).contains("\"v1\"").contains("CURRENT");
        assertThat(get("/public-api/changelog", null).body()).contains("1.0.0");
        assertThat(get("/public-api/docs", null).body()).contains("swagger-ui");
        assertThat(get("/public-api/docs/changelog", null).body()).contains("Versões e changelog");
        assertThat(get("/public-api/docs/assets/swagger-ui-bundle.js", null).statusCode()).isEqualTo(200);
        assertThat(get("/public-api/v1/openapi.json", null).body()).contains("\"openapi\"");
        HttpResponse<String> unknownVersion = get("/public-api/v9/titles", null);
        assertThat(unknownVersion.statusCode()).isEqualTo(404);
        assertThat(body(unknownVersion)).containsEntry("code", "public_api.version_unknown");
        // corpo ilegível: RFC 9457 com código estável
        IssuedToken token = issue(creditorA, Set.of("portfolios:write"), null, List.of());
        HttpResponse<String> malformed = call("PUT", "/public-api/v1/portfolios/" + portfolioA, token.token(), "{x",
                Map.of());
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(body(malformed)).containsEntry("code", "request.malformed").containsKey("requestId");
        HttpResponse<String> invalid = call("PUT", "/public-api/v1/portfolios/" + portfolioA, token.token(), "{}", Map.of());
        assertThat(body(invalid)).containsEntry("code", "validation.failed").containsKey("errors");
        // a documentação interna continua protegida
        assertThat(get("/v3/api-docs/interna", null).statusCode()).isEqualTo(401);
    }
}
