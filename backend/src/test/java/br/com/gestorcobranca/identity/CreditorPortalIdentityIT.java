package br.com.gestorcobranca.identity;

import br.com.gestorcobranca.identity.application.port.in.CreditorPortalAdministration;
import br.com.gestorcobranca.identity.application.port.in.CreditorPortalAdministration.InviteCommand;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorStepUp;
import br.com.gestorcobranca.identity.application.port.out.IdentityMailer;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorRole;
import br.com.gestorcobranca.identity.domain.service.TotpTestCodes;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.StepUpProofs;
import br.com.gestorcobranca.shared.security.StepUpPurpose;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

/**
 * Identidade da pessoa do credor (ADR 0011, fatia 2): convite pela assessoria, ativação com senha e MFA numa transação,
 * login em duas etapas, token só no portal do credor, revogação imediata, refresh rotativo com detecção de reuso,
 * respostas iguais (anti-enumeração), bloqueio e a nova verificação do MFA.
 */
class CreditorPortalIdentityIT extends IntegrationTest {

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
    CreditorStepUp stepUp;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    JsonMapper json;
    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private UUID tenant;
    private String tenantSlug;
    private UUID admin;
    private UUID creditorA;
    private UUID creditorB;

    @BeforeEach
    void setUp() {
        tenantSlug = "cred-" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.ProvisionedTenant provisioned = tenantExecution.callInTenant(Ids.newId(), "test.setup",
                () -> provisioning.provision(new ProvisionTenantCommand(tenantSlug, "Assessoria Credor Ltda", "Credor",
                        "11.444.777/0001-61", "Admin", "admin@" + tenantSlug + ".test")));
        tenant = provisioned.tenant().id();
        admin = provisioned.adminUserId();
        creditorA = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81",
                null, null)).id());
        creditorB = system(() -> creditors.create(new CreditorCommand("Loja B S.A.", "Loja B", "45.997.418/0001-53",
                null, null)).id());
        asAdmin(() -> administration.enable(creditorA));
        clearInvocations(mailer);
    }

    @Test
    void conviteAtivacaoLoginEFronteiraDoToken() {
        String email = "ana@banco-a.test";
        String invitation = invite(creditorA, "Ana do Banco", email, "FINANCEIRO");

        // regras do convite: e-mail de usuário da assessoria, credor sem portal, e-mail repetido
        assertThatThrownBy(() -> asAdmin(() -> administration.invite(creditorA, new InviteCommand("Admin",
                "admin@" + tenantSlug + ".test", "LEITURA"))))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "creditor_user.internal_email");
        // a recusa fica auditada mesmo com o rollback do convite (decisão 1395)
        assertThat(count("select count(*) from shared.audit_event where action = 'creditor_user.invite_refused'")).isEqualTo(1);
        // gestor com escopo só do credor B não administra o portal do credor A
        Actor onlyB = new Actor(Actor.ActorType.USER, admin, tenant, Ids.newId(), DefaultRoles.GESTOR,
                DefaultRoles.forTenant().stream().filter(d -> d.code().equals(DefaultRoles.GESTOR)).findFirst().orElseThrow()
                        .permissions(), CreditorScope.of(Set.of(creditorB)));
        assertThatThrownBy(() -> tenantExecution.callAs(onlyB, () -> administration.status(creditorA)))
                .isInstanceOfAny(br.com.gestorcobranca.shared.security.ScopeAccessDeniedException.class,
                        br.com.gestorcobranca.shared.domain.ResourceNotFoundException.class);
        assertThatThrownBy(() -> asAdmin(() -> administration.invite(creditorB, new InviteCommand("Bia", "bia@loja-b.test",
                "LEITURA")))).hasFieldOrPropertyWithValue("code", "creditor_portal.disabled");
        assertThatThrownBy(() -> asAdmin(() -> administration.invite(creditorA, new InviteCommand("Ana 2", email,
                "LEITURA")))).hasFieldOrPropertyWithValue("code", "creditor_user.email_taken");
        // o financeiro da assessoria não convida (decisão 1394)
        assertThatThrownBy(() -> as(DefaultRoles.FINANCEIRO, () -> administration.status(creditorA)))
                .isInstanceOf(br.com.gestorcobranca.shared.security.ScopeAccessDeniedException.class);

        // sem Origin: recusado (RS-20)
        assertThat(send(post("/api/creditor/v1/auth/activation/start", Map.of("token", invitation)), null, false).status())
                .isEqualTo(401);
        Response start = call(post("/api/creditor/v1/auth/activation/start", Map.of("token", invitation)), null);
        assertThat(start.status()).isEqualTo(200);
        assertThat(start.json().get("creditorName").asString()).isEqualTo("Banco A");
        assertThat(start.json().get("account").asString()).doesNotContain("ana@");
        String secret = start.json().get("secret").asString();

        // código errado: o convite continua válido; certo: ativa e já abre a sessão
        Response wrong = call(post("/api/creditor/v1/auth/activation/complete", Map.of("token", invitation,
                "newPassword", PASSWORD, "code", other(TotpTestCodes.code(secret, 0)))), null);
        assertThat(wrong.status()).isEqualTo(422);
        assertThat(wrong.json().get("code").asString()).isEqualTo("creditor_activation.invalid_code");
        long activationStep = TotpTestCodes.stepNow();
        Response activated = call(post("/api/creditor/v1/auth/activation/complete", Map.of("token", invitation,
                "newPassword", PASSWORD, "code", TotpTestCodes.codeAt(secret, activationStep))), null);
        assertThat(activated.status()).isEqualTo(200);
        assertThat(activated.json().get("status").asString()).isEqualTo("AUTHENTICATED");
        assertThat(activated.cookie()).startsWith("gc_creditor_refresh=").contains("HttpOnly").contains("SameSite=Strict")
                .contains("Path=/api/creditor/v1/auth");
        String token = activated.json().get("accessToken").asString();
        // convite de uso único: a mesma recusa de link inválido
        Response reused = call(post("/api/creditor/v1/auth/activation/start", Map.of("token", invitation)), null);
        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.json().get("code").asString()).isEqualTo("creditor_link.invalid");

        // o token do credor só vale no portal do credor
        Response me = call(get("/api/creditor/v1/me"), token);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().get("creditorName").asString()).isEqualTo("Banco A");
        assertThat(me.json().get("role").asString()).isEqualTo("FINANCEIRO");
        assertThat(me.json().get("permissions").toString()).doesNotContain("SETTLEMENT", "CASE_", "PAYMENT_VIEW");
        assertThat(call(get("/api/v1/creditors"), token).status()).isEqualTo(403);
        assertThat(call(get("/api/portal/v1/me/session"), token).status()).isEqualTo(403);

        // login: senha errada e e-mail inexistente respondem igual
        Response badPassword = login(email, "senha-errada-123456");
        Response unknown = login("ninguem@banco-a.test", "senha-errada-123456");
        assertThat(badPassword.status()).isEqualTo(401).isEqualTo(unknown.status());
        assertThat(badPassword.json().get("code").asString()).isEqualTo(unknown.json().get("code").asString());
        assertThat(badPassword.json().get("detail").asString()).isEqualTo(unknown.json().get("detail").asString());

        Response step = login(email, PASSWORD);
        assertThat(step.json().get("status").asString()).isEqualTo("MFA_REQUIRED");
        assertThat(step.json().get("accessToken").isNull()).isTrue();
        String stepToken = step.json().get("stepToken").asString();
        // a etapa do credor nunca serve ao login interno
        assertThat(call(post("/api/v1/auth/mfa/verify", Map.of("stepToken", stepToken, "code", "123456")), null).status())
                .isEqualTo(401);
        // código já usado na ativação (mesmo passo): recusado; o passo seguinte entra
        assertThat(call(post("/api/creditor/v1/auth/mfa", Map.of("stepToken", stepToken,
                "code", TotpTestCodes.codeAt(secret, activationStep))), null).status()).isEqualTo(401);
        Response second = call(post("/api/creditor/v1/auth/mfa", Map.of("stepToken", stepToken,
                "code", TotpTestCodes.codeAt(secret, activationStep + 1))), null);
        assertThat(second.json().get("status").asString()).isEqualTo("AUTHENTICATED");
        String token2 = second.json().get("accessToken").asString();
        assertThat(call(get("/api/creditor/v1/me"), token2).status()).isEqualTo(200);

        // refresh rotativo: o cookie usado de novo revoga a sessão (reuso = roubo)
        String cookie = activated.cookie().split(";")[0];
        Response refreshed = call(post("/api/creditor/v1/auth/refresh", Map.of()).header("Cookie", cookie), null);
        assertThat(refreshed.status()).isEqualTo(200);
        Response reuse = call(post("/api/creditor/v1/auth/refresh", Map.of()).header("Cookie", cookie), null);
        assertThat(reuse.status()).isEqualTo(401);
        assertThat(call(get("/api/creditor/v1/me"), refreshed.json().get("accessToken").asString()).status()).isEqualTo(401);

        // revogação pela assessoria: vale na requisição seguinte (resolvedor sem cache)
        UUID userId = UUID.fromString(me.json().get("userId").asString());
        asAdmin(() -> administration.revoke(creditorA, userId, "Saiu do banco"));
        assertThat(call(get("/api/creditor/v1/me"), token2).status()).isEqualTo(401);
        // o cookie da sessão revogada responde 401 sem alerta de roubo (só o reuso acima alertou)
        assertThat(call(post("/api/creditor/v1/auth/refresh", Map.of()).header("Cookie", second.cookie().split(";")[0]), null)
                .status()).isEqualTo(401);
        verify(mailer, org.mockito.Mockito.times(1)).sendSecurityAlert(eq(email), any(), any(), any());
        assertThat(login(email, PASSWORD).status()).isEqualTo(401);

        // rastros: auditoria, log de acesso com o tipo do ator e eventos de negócio sem e-mail
        assertThat(count("select count(*) from shared.audit_event where action = 'creditor_user.activated'")).isEqualTo(1);
        assertThat(count("select count(*) from shared.access_log where actor_type = 'CREDITOR' and event_type = 'LOGIN_SUCCESS'"))
                .isGreaterThanOrEqualTo(2);
        assertThat(system(() -> jdbcTemplate.queryForObject("select coalesce(string_agg(attributes::text, ' '), '') "
                + "from shared.business_event where event_type like 'creditor_portal.%'", String.class)))
                .doesNotContain(email, "Ana");
    }

    /**
     * Opção D do MFA no sandbox (fatia 8, RS-80): o segredo do autenticador é aleatório, gerado pelo servidor na abertura
     * da ativação — dois convites recebem segredos diferentes, de 160 bits em Base32 (32 caracteres), e o segredo
     * pendente se mantém ao reabrir o mesmo link (o QR já lido continua valendo). Nenhum caminho aceita segredo de fora.
     */
    @Test
    void segredoDoAutenticadorAleatorioPorConvite() {
        String first = invite(creditorA, "Duda", "duda@banco-a.test", "LEITURA");
        String second = invite(creditorA, "Edu", "edu@banco-a.test", "LEITURA");
        String secretA = call(post("/api/creditor/v1/auth/activation/start", Map.of("token", first)), null).json()
                .get("secret").asString();
        String secretB = call(post("/api/creditor/v1/auth/activation/start", Map.of("token", second)), null).json()
                .get("secret").asString();
        assertThat(secretA).matches("[A-Z2-7]{32}").isNotEqualTo(secretB);
        assertThat(secretB).matches("[A-Z2-7]{32}");
        assertThat(call(post("/api/creditor/v1/auth/activation/start", Map.of("token", first)), null).json()
                .get("secret").asString()).isEqualTo(secretA);
        // o segredo fica cifrado no banco, nunca em claro
        assertThat(count("select count(*) from identity.creditor_user where mfa_secret_encrypted = '" + secretA + "'"))
                .isZero();
    }

    @Test
    void portalDesativadoDerrubaSessoesEBloqueioPorTentativas() {
        String email = "caio@banco-a.test";
        Session caio = activate(invite(creditorA, "Caio", email, "GESTOR"));
        assertThat(call(get("/api/creditor/v1/me"), caio.token()).status()).isEqualTo(200);

        asAdmin(() -> administration.disable(creditorA));
        assertThat(call(get("/api/creditor/v1/me"), caio.token()).status()).isEqualTo(401);
        assertThat(login(email, PASSWORD).status()).isEqualTo(401);
        asAdmin(() -> administration.enable(creditorA));

        // credor desativado no cadastro (fim do contrato): o portal fecha na requisição seguinte, sem mexer na liberação
        Session eva = activate(invite(creditorA, "Eva", "eva@banco-a.test", "LEITURA"));
        system(() -> creditors.changeStatus(creditorA, false));
        assertThat(call(get("/api/creditor/v1/me"), eva.token()).status()).isEqualTo(401);
        system(() -> creditors.changeStatus(creditorA, true));

        // 5 falhas bloqueiam a conta: a senha certa não entra enquanto durar o bloqueio
        for (int i = 0; i < 5; i++) {
            assertThat(login(email, "senha-errada-" + i + "-abcdef").status()).isEqualTo(401);
        }
        assertThat(login(email, PASSWORD).status()).isEqualTo(401);
        assertThat(count("select count(*) from shared.access_log where actor_type = 'CREDITOR' and event_type = 'LOGIN_BLOCKED'"))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void novaVerificacaoNaAcaoRecusaCodigoRepetidoEBloqueiaNoLimite() {
        Session dora = activate(invite(creditorA, "Dora", "dora@banco-a.test", "FINANCEIRO"));
        Actor actor = Actor.creditor(tenant, dora.userId(), dora.sessionId(), creditorA, CreditorRole.FINANCEIRO.permissions());

        // o passo da ativação não serve; o seguinte sim, uma vez
        assertThatThrownBy(() -> inAction(actor, () -> stepUp.verify(
                TotpTestCodes.codeAt(dora.secret(), dora.activationStep()), PURPOSE, "alvo")))
                .hasFieldOrPropertyWithValue("code", "creditor_step_up.invalid");
        // sem a transação da ação, a verificação nem começa (MANDATORY)
        assertThatThrownBy(() -> stepUp.verify(TotpTestCodes.codeAt(dora.secret(), dora.activationStep() + 1), PURPOSE,
                "alvo")).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        // comprovante de uso único, preso à transação, à finalidade e ao alvo (SEG-F07)
        String[] kept = new String[1];
        Instant verified = inAction(actor, () -> {
            String proof = stepUp.verify(TotpTestCodes.codeAt(dora.secret(), dora.activationStep() + 1), PURPOSE, "alvo");
            kept[0] = proof;
            assertThatThrownBy(() -> stepUpProofs.consume(proof, PURPOSE, "outro alvo"))
                    .hasFieldOrPropertyWithValue("code", "creditor_step_up.proof_invalid");
            assertThatThrownBy(() -> stepUpProofs.consume(proof, StepUpPurpose.STATEMENT_ACKNOWLEDGE, "alvo"))
                    .hasFieldOrPropertyWithValue("code", "creditor_step_up.proof_invalid");
            // outra pessoa na mesma transação não usa o comprovante
            org.springframework.security.core.context.SecurityContext own =
                    org.springframework.security.core.context.SecurityContextHolder.getContext();
            org.springframework.security.core.context.SecurityContext other =
                    org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            other.setAuthentication(br.com.gestorcobranca.shared.security.ActorAuthentication.of(Actor.creditor(tenant,
                    Ids.newId(), dora.sessionId(), creditorA, CreditorRole.FINANCEIRO.permissions())));
            org.springframework.security.core.context.SecurityContextHolder.setContext(other);
            try {
                assertThatThrownBy(() -> stepUpProofs.consume(proof, PURPOSE, "alvo"))
                        .hasFieldOrPropertyWithValue("code", "creditor_step_up.proof_invalid");
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.setContext(own);
            }
            // a mesma pessoa numa transação aninhada (REQUIRES_NEW) também não usa
            assertThatThrownBy(() -> tenantExecution.callAs(actor, () -> stepUpProofs.consume(proof, PURPOSE, "alvo")))
                    .hasFieldOrPropertyWithValue("code", "creditor_step_up.proof_invalid");
            return stepUpProofs.consume(proof, PURPOSE, "alvo");
        });
        assertThat(verified).isNotNull();
        // depois do fim da transação, o mesmo comprovante não vale em outra (nem na mesma thread)
        assertThatThrownBy(() -> inAction(actor, () -> stepUpProofs.consume(kept[0], PURPOSE, "alvo")))
                .hasFieldOrPropertyWithValue("code", "creditor_step_up.proof_invalid");

        // as falhas sobrevivem ao rollback da ação e, no limite, derrubam a sessão
        // código inválido conta (o reuso não): 5 chegam ao limite
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> inAction(actor, () -> stepUp.verify(other(TotpTestCodes.codeAt(dora.secret(), dora.activationStep() + 1)), PURPOSE, "alvo")))
                    .isInstanceOf(BusinessRuleException.class);
        }
        assertThat(call(get("/api/creditor/v1/me"), dora.token()).status()).isEqualTo(401);
        verify(mailer, atLeastOnce()).sendSecurityAlert(eq("dora@banco-a.test"), any(), any(), any());
    }

    @Test
    void senhaVencidaSoDepoisDoMfaEtapaDeUsoUnicoERedefinicaoComIntervalo() {
        String email = "fabio@banco-a.test";
        Session fabio = activate(invite(creditorA, "Fábio", email, "GESTOR"));
        system(() -> jdbcTemplate.update("update identity.creditor_user set password_changed_at = now() - interval '400 days' "
                + "where id = ?", fabio.userId()));

        Response step = login(email, PASSWORD);
        Response mfa = call(post("/api/creditor/v1/auth/mfa", Map.of("stepToken", step.json().get("stepToken").asString(),
                "code", TotpTestCodes.codeAt(fabio.secret(), fabio.activationStep() + 1))), null);
        assertThat(mfa.json().get("status").asString()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
        assertThat(mfa.json().get("accessToken").isNull()).isTrue();
        String pwdStep = mfa.json().get("stepToken").asString();
        Response changed = call(post("/api/creditor/v1/auth/password/expired", Map.of("stepToken", pwdStep,
                "newPassword", PASSWORD + "-nova")), null);
        assertThat(changed.json().get("status").asString()).isEqualTo("AUTHENTICATED");
        // a etapa vale uma vez; a sessão aberta antes da troca cai
        assertThat(call(post("/api/creditor/v1/auth/password/expired", Map.of("stepToken", pwdStep,
                "newPassword", PASSWORD + "-outra")), null).status()).isEqualTo(401);
        assertThat(call(get("/api/creditor/v1/me"), fabio.token()).status()).isEqualTo(401);
        assertThat(call(get("/api/creditor/v1/me"), changed.json().get("accessToken").asString()).status()).isEqualTo(200);

        // esqueci a senha: 202 com e sem conta; pedidos em série mandam um link só
        assertThat(call(post("/api/creditor/v1/auth/password/forgot", Map.of("tenant", tenantSlug, "email", email)), null)
                .status()).isEqualTo(202);
        assertThat(call(post("/api/creditor/v1/auth/password/forgot", Map.of("tenant", tenantSlug, "email", email)), null)
                .status()).isEqualTo(202);
        assertThat(call(post("/api/creditor/v1/auth/password/forgot", Map.of("tenant", tenantSlug,
                "email", "ninguem@banco-a.test")), null).status()).isEqualTo(202);
        verify(mailer, org.mockito.Mockito.times(1)).sendCreditorPasswordReset(eq(email), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ apoio

    private record Session(UUID userId, UUID sessionId, String token, String secret, long activationStep) {
    }

    private String invite(UUID creditorId, String name, String email, String role) {
        asAdmin(() -> administration.invite(creditorId, new InviteCommand(name, email, role)));
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailer, atLeastOnce()).sendCreditorInvitation(eq(email), any(), any(), any(), link.capture(), any());
        String value = link.getValue();
        assertThat(value).contains("/credor/" + tenantSlug + "/ativar#token=");
        return value.substring(value.indexOf("#token=") + 7);
    }

    private Session activate(String invitation) {
        String secret = call(post("/api/creditor/v1/auth/activation/start", Map.of("token", invitation)), null).json()
                .get("secret").asString();
        long step = TotpTestCodes.stepNow();
        Response activated = call(post("/api/creditor/v1/auth/activation/complete", Map.of("token", invitation,
                "newPassword", PASSWORD, "code", TotpTestCodes.codeAt(secret, step))), null);
        assertThat(activated.status()).isEqualTo(200);
        String token = activated.json().get("accessToken").asString();
        JsonNode me = call(get("/api/creditor/v1/me"), token).json();
        UUID userId = UUID.fromString(me.get("userId").asString());
        UUID sessionId = system(() -> jdbcTemplate.queryForObject(
                "select id from identity.creditor_session where user_id = ? and status = 'ACTIVE'", UUID.class, userId));
        return new Session(userId, sessionId, token, secret, step);
    }

    private Response login(String email, String password) {
        return call(post("/api/creditor/v1/auth/login", Map.of("tenant", tenantSlug, "email", email, "password", password)),
                null);
    }

    private static String other(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    private <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    private <T> T as(String role, Supplier<T> action) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst()
                .orElseThrow().permissions();
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, admin, tenant, Ids.newId(), role, permissions,
                CreditorScope.ALL), action);
    }

    private <T> T asAdmin(Supplier<T> action) {
        return as(DefaultRoles.ADMIN, action);
    }

    private static final StepUpPurpose PURPOSE = StepUpPurpose.PAYMENT_REPORT;

    @Autowired
    StepUpProofs stepUpProofs;
    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** A ação do portal: o ator do credor e a transação da ação (onde o comprovante vale). */
    private <T> T inAction(Actor actor, Supplier<T> action) {
        return tenantExecution.callAs(actor, () -> new org.springframework.transaction.support.TransactionTemplate(
                transactionManager).execute(status -> action.get()));
    }

    private <T> T asCreditor(Actor actor, Supplier<T> action) {
        return tenantExecution.callAs(actor, action);
    }

    private long count(String sql) {
        return system(() -> {
            Long value = jdbcTemplate.queryForObject(sql + (sql.contains(" where ") ? " and" : " where")
                    + " tenant_id = ?", Long.class, tenant);
            return value == null ? 0L : value;
        });
    }

    // ------------------------------------------------------------------ HTTP

    private record Response(int status, JsonNode json, String cookie) {
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(uri(path)).GET();
    }

    private HttpRequest.Builder post(String path, Object body) {
        return HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private Response call(HttpRequest.Builder builder, String token) {
        return send(builder, token, true);
    }

    private Response send(HttpRequest.Builder builder, String token, boolean origin) {
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (origin) {
            builder.header("Origin", "http://localhost:" + port);
        }
        builder.header("User-Agent", "CreditorPortalIT/1.0");
        try {
            HttpResponse<String> r = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String body = r.body();
            JsonNode node = body == null || body.isBlank() || !body.startsWith("{") ? null : json.readTree(body);
            return new Response(r.statusCode(), node, r.headers().firstValue("Set-Cookie").orElse(""));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
