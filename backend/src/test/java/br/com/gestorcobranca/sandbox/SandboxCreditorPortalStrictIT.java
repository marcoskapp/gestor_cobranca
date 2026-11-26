package br.com.gestorcobranca.sandbox;

import br.com.gestorcobranca.identity.application.port.in.CreditorPortalAdministration;
import br.com.gestorcobranca.identity.application.port.in.CreditorPortalAdministration.InviteCommand;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.application.port.out.IdentityMailer;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.identity.domain.service.TotpTestCodes;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Com o perfil {@code sandbox} o portal do credor continua estrito (RS-85): o login da pessoa do credor sempre pede o
 * código do autenticador ({@code MFA_REQUIRED}, sem token de acesso) e as rotas de autenticação exigem {@code Origin}
 * — o "sem MFA" do sandbox (decisão 633) vale só para os usuários internos. O perfil test vem depois e sobrepõe banco,
 * chaves e diretórios locais, como no {@link SandboxApiDocsOpenIT}.
 */
@ActiveProfiles({"sandbox", "test"})
@TestPropertySource(properties = {
        "app.environment=sandbox",
        "app.storage.local-root=./target/test-storage-sandbox",
        "app.collection.inbound-mail.local.directory=./target/inbound-mail-sandbox",
        "app.logging.dir=./target/test-logs-sandbox",
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=9"})
class SandboxCreditorPortalStrictIT extends IntegrationTest {

    private static final String PASSWORD = "Senha-forte-do-credor-2026";

    @MockitoBean
    IdentityMailer mailer;
    @Autowired
    TenantProvisioning provisioning;
    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    CreditorUseCases creditors;
    @Autowired
    CreditorPortalAdministration administration;
    @Autowired
    JsonMapper json;
    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @Test
    void creditorLoginAlwaysAsksForTheAuthenticatorInTheSandboxProfile() throws Exception {
        String slug = "sbx-" + UUID.randomUUID().toString().substring(0, 8);
        var provisioned = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> provisioning.provision(
                new ProvisionTenantCommand(slug, "Assessoria Sandbox Ltda", "Sandbox", "11.444.777/0001-61", "Admin",
                        "admin@" + slug + ".test")));
        UUID tenant = provisioned.tenant().id();
        Set<Permission> adminPermissions = DefaultRoles.forTenant().stream()
                .filter(d -> d.code().equals(DefaultRoles.ADMIN)).findFirst().orElseThrow().permissions();
        Actor admin = new Actor(Actor.ActorType.USER, provisioned.adminUserId(), tenant, Ids.newId(), DefaultRoles.ADMIN,
                adminPermissions, CreditorScope.ALL);
        UUID creditor = tenantExecution.callInTenant(tenant, "test", () -> creditors.create(new CreditorCommand(
                "Banco Sandbox S.A.", "Banco Sandbox", "11.222.333/0001-81", null, null)).id());
        String email = "pessoa@banco-sandbox.test";
        tenantExecution.callAs(admin, () -> administration.enable(creditor));
        tenantExecution.callAs(admin, () -> administration.invite(creditor, new InviteCommand("Pessoa", email, "LEITURA")));
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailer, atLeastOnce()).sendCreditorInvitation(eq(email), any(), any(), any(), link.capture(), any());
        String invitation = link.getValue().substring(link.getValue().indexOf("#token=") + 7);

        // sem Origin, a ativação é recusada como em produção
        assertThat(post("/api/creditor/v1/auth/activation/start", Map.of("token", invitation), false).statusCode())
                .isEqualTo(401);
        String secret = body(post("/api/creditor/v1/auth/activation/start", Map.of("token", invitation), true))
                .get("secret").asString();
        long step = TotpTestCodes.stepNow();
        HttpResponse<String> activated = post("/api/creditor/v1/auth/activation/complete", Map.of("token", invitation,
                "newPassword", PASSWORD, "code", TotpTestCodes.codeAt(secret, step)), true);
        assertThat(activated.statusCode()).isEqualTo(200);

        HttpResponse<String> login = post("/api/creditor/v1/auth/login", Map.of("tenant", slug, "email", email,
                "password", PASSWORD), true);
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode first = body(login);
        assertThat(first.get("status").asString()).isEqualTo("MFA_REQUIRED");
        assertThat(first.get("accessToken").isNull()).isTrue();
        assertThat(login.headers().firstValue("Set-Cookie")).isEmpty();
        // o código já usado na ativação não abre sessão (anti-reuso também no sandbox)
        assertThat(post("/api/creditor/v1/auth/mfa", Map.of("stepToken", first.get("stepToken").asString(),
                "code", TotpTestCodes.codeAt(secret, step)), true).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> post(String path, Object body, boolean origin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("User-Agent", "SandboxCreditorPortalStrictIT/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (origin) {
            builder.header("Origin", "http://localhost:" + port);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }
}
