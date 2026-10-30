package br.com.gestorcobranca.publicapi;

import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.CreatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractTermsCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.CreateContractCommand;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken.IssueTokenCommand;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken.IssuedToken;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Cenário da API pública: assessoria com dois credores e uma carteira ativa em cada; chamadas HTTP reais. */
abstract class PublicApiTestSupport extends IntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantAdministration tenantAdministration;
    @Autowired
    CreditorUseCases creditors;
    @Autowired
    ServiceContractUseCases contracts;
    @Autowired
    PortfolioUseCases portfolios;
    @Autowired
    IssueIntegrationToken issuer;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonCodec json;

    final HttpClient http = HttpClient.newHttpClient();
    UUID tenant;
    UUID creditorA;
    UUID creditorB;
    UUID portfolioA;
    UUID portfolioB;
    UUID managerId;

    @BeforeEach
    void setUpScenario() {
        String slug = "pa-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, "Assessoria API Ltda", "Assessoria API", "11.444.777/0001-61")).id());
        creditorA = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81",
                null, null)).id());
        creditorB = system(() -> creditors.create(new CreditorCommand("Loja B S.A.", "Loja B", "45.997.418/0001-53",
                null, null)).id());
        portfolioA = activePortfolio(creditorA, "A-01");
        portfolioB = activePortfolio(creditorB, "B-01");
        managerId = Ids.newId();
    }

    UUID activePortfolio(UUID creditorId, String code) {
        return system(() -> {
            UUID contract = contracts.create(new CreateContractCommand(creditorId, "CT-" + code,
                    new ContractTermsCommand(LocalDate.of(2026, 1, 1), null, null, 10, null))).id();
            UUID id = portfolios.create(new CreatePortfolioCommand(creditorId, contract, code, "Carteira " + code, null,
                    LocalDate.of(2026, 1, 1), null)).id();
            portfolios.changeStatus(id, "ACTIVE", null);
            return id;
        });
    }

    <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    /** Gestor com todas as permissões da API pública e escopo de todos os credores. */
    <T> T asManager(Supplier<T> action) {
        return asUser(managerId, EnumSet.allOf(Permission.class), CreditorScope.ALL, action);
    }

    <T> T asUser(UUID id, Set<Permission> permissions, CreditorScope scope, Supplier<T> action) {
        Set<Permission> perms = EnumSet.copyOf(permissions);
        perms.remove(Permission.TENANT_MANAGE);
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, id, tenant, Ids.newId(), "gestor", perms, scope),
                action);
    }

    IssuedToken issue(UUID creditorId, Set<String> scopes, Integer rateLimit, List<String> ips) {
        return asManager(() -> issuer.issue(new IssueTokenCommand(creditorId, "ERP " + creditorId.toString().substring(0, 4),
                "integração de teste", scopes, rateLimit, ips, null)));
    }

    Set<String> allScopes() {
        return Set.of("portfolios:read", "portfolios:write", "imports:read", "imports:write", "titles:read",
                "titles:write", "debtors:read", "debtors:write", "collection:read", "agreements:read", "payments:read",
                "payments:write", "disputes:read", "disputes:write", "billing:read", "settlement:read");
    }

    HttpResponse<String> call(String method, String path, String token, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        headers.forEach(b::header);
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path, String token) throws IOException, InterruptedException {
        return call("GET", path, token, null, Map.of());
    }

    Map<String, Object> body(HttpResponse<String> response) {
        return json.toMap(response.body());
    }
}
