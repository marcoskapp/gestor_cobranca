package br.com.gestorcobranca.identity;

import br.com.gestorcobranca.creditorportal.application.port.in.CreditorActivityPortalUseCases;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorActivityPortalUseCases.ActivityItem;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorActivityPortalUseCases.ActivityPage;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorActivityQueries;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorRole;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import br.com.gestorcobranca.support.JsonContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Minha atividade" do portal do credor (fatia 7; RS-47 a RS-49): só o ator {@code CREDITOR} e só as pessoas do credor
 * do ator (incluída a revogada), nunca pela coluna {@code creditor_id} da auditoria; lista branca de ações (o
 * {@code payment.informed} repetido e as ações da assessoria ficam fora); visão do time só com
 * {@code CREDITOR_ACTIVITY_VIEW} e registrada; nome da pessoa da assessoria, e-mail e IP em nenhum campo; paginação por
 * cursor opaco; contrato por snapshot.
 */
class CreditorActivityIT extends IntegrationTest {

    private static final String AGENCY_NAME = "Zélia Interna da Assessoria";

    @Autowired
    TenantProvisioning provisioning;
    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    CreditorUseCases creditors;
    @Autowired
    AuditTrail auditTrail;
    @Autowired
    AccessLogger accessLogger;
    @Autowired
    CreditorActivityPortalUseCases portal;
    @Autowired
    CreditorActivityQueries activity;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    WebApplicationContext context;
    @Autowired
    JsonMapper json;

    private UUID tenant;

    @Test
    void soAPropriaAtividadeEADoTimeComPermissaoPelaListaBranca() throws Exception {
        String slug = "act-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> provisioning.provision(
                new ProvisionTenantCommand(slug, "Assessoria Atividade Ltda", "Atividade", "11.444.777/0001-61",
                        "Admin", "admin@" + slug + ".test"))).tenant().id();
        UUID creditorA = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A",
                "11.222.333/0001-81", null, null)).id());
        UUID creditorB = system(() -> creditors.create(new CreditorCommand("Loja B S.A.", "Loja B",
                "45.997.418/0001-53", null, null)).id());
        UUID ana = creditorUser(creditorA, "Ana do Banco", "ana@banco-a.test", "LEITURA", "INVITED");
        UUID bia = creditorUser(creditorA, "Bia do Banco", "bia@banco-a.test", "GESTOR", "REVOKED");
        UUID caio = creditorUser(creditorB, "Caio da Loja", "caio@loja-b.test", "GESTOR", "INVITED");
        UUID statement = Ids.newId();
        UUID payment = Ids.newId();
        UUID dispute = Ids.newId();
        UUID file = Ids.newId();

        // Ana (credor A): aceite, informe (com o payment.informed repetido), login, download do portal; download sem a
        // origem do portal e falha de MFA ficam fora
        audit(creditor(ana, creditorA), "settlement_statement.acknowledged", "settlement_statement", statement, creditorA);
        audit(creditor(ana, creditorA), "payment.informed", "payment", payment, creditorA);
        audit(creditor(ana, creditorA), "payment.creditor_portal_reported", "payment", payment, creditorA);
        access(AccessEventType.LOGIN_SUCCESS, ana, Map.of("portal", "creditor"));
        access(AccessEventType.FILE_DOWNLOADED, ana, Map.of("resource", "statement_export", "resourceId",
                file.toString(), "origin", "creditor_portal"));
        access(AccessEventType.FILE_DOWNLOADED, ana, Map.of("resource", "statement_export", "resourceId",
                file.toString()));
        access(AccessEventType.MFA_FAILURE, ana, Map.of("purpose", "statement_acknowledge"));
        // Bia (revogada, credor A): a resposta à contestação continua na atividade do time
        audit(creditor(bia, creditorA), "dispute.creditor_responded", "dispute", dispute, creditorA);
        // Caio (credor B) numa ação marcada com o credor A: não aparece para o credor A (o filtro é pela pessoa)
        audit(creditor(caio, creditorB), "settlement_statement.contested", "settlement_statement", statement, creditorA);
        access(AccessEventType.LOGIN_SUCCESS, caio, Map.of("portal", "creditor"));
        // a assessoria, sobre o credor A — inclusive com o mesmo id da Ana, mas outro tipo de ator
        Actor agency = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), AGENCY_NAME,
                EnumSet.of(Permission.CREDITOR_VIEW), CreditorScope.ALL);
        audit(agency, "dispute.creditor_requested", "dispute", dispute, creditorA);
        audit(agency, "creditor_user.revoked", "creditor_user", bia, creditorA);
        audit(new Actor(Actor.ActorType.USER, ana, tenant, Ids.newId(), AGENCY_NAME, EnumSet.of(Permission.CREDITOR_VIEW),
                CreditorScope.ALL), "settlement_statement.acknowledged", "settlement_statement", statement, creditorA);

        // ---- a própria atividade
        ActivityPage own = as(ana, creditorA, CreditorRole.LEITURA.permissions(), () -> portal.activity(false, null, 50));
        assertThat(own.scope()).isEqualTo("OWN");
        assertThat(own.windowDays()).isEqualTo(90);
        assertThat(own.items()).extracting(ActivityItem::action).containsExactlyInAnyOrder("STATEMENT_ACKNOWLEDGED",
                "PAYMENT_REPORTED", "SIGNED_IN", "FILE_DOWNLOADED");
        assertThat(own.items()).allSatisfy(i -> {
            assertThat(i.mine()).isTrue();
            assertThat(i.personName()).isEqualTo("Ana do Banco");
        });
        assertThat(own.items()).filteredOn(i -> i.action().equals("STATEMENT_ACKNOWLEDGED")).singleElement()
                .satisfies(i -> {
                    assertThat(i.referenceType()).isEqualTo("STATEMENT");
                    assertThat(i.referenceId()).isEqualTo(statement);
                });
        assertThat(own.items()).filteredOn(i -> i.action().equals("FILE_DOWNLOADED")).singleElement()
                .satisfies(i -> assertThat(i.referenceType()).isEqualTo("STATEMENT_FILE"));
        assertThat(own.items()).filteredOn(i -> i.action().equals("SIGNED_IN")).singleElement()
                .satisfies(i -> assertThat(i.referenceType()).isNull());
        assertThat(own.nextCursor()).isNull();

        // paginação por cursor opaco: 2 + 2, sem repetir
        ActivityPage first = as(ana, creditorA, CreditorRole.LEITURA.permissions(), () -> portal.activity(false, null, 2));
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isNotBlank();
        ActivityPage second = as(ana, creditorA, CreditorRole.LEITURA.permissions(),
                () -> portal.activity(false, first.nextCursor(), 2));
        assertThat(second.items()).hasSize(2);
        assertThat(second.nextCursor()).isNull();
        Set<String> seen = new HashSet<>();
        first.items().forEach(i -> seen.add(i.action()));
        second.items().forEach(i -> seen.add(i.action()));
        assertThat(seen).hasSize(4);

        // ---- visão do time: só com CREDITOR_ACTIVITY_VIEW (gestão e compliance)
        long denied = count("select count(*) from shared.access_log where event_type = 'ACCESS_DENIED' and tenant_id = ?");
        for (CreditorRole role : List.of(CreditorRole.LEITURA, CreditorRole.JURIDICO, CreditorRole.FINANCEIRO)) {
            assertThatThrownBy(() -> as(ana, creditorA, role.permissions(), () -> portal.activity(true, null, 20)))
                    .as(role.name()).isInstanceOf(ScopeAccessDeniedException.class);
        }
        assertThat(count("select count(*) from shared.access_log where event_type = 'ACCESS_DENIED' and tenant_id = ?"))
                .isEqualTo(denied + 3);
        UUID compliancePerson = Ids.newId();
        ActivityPage team = as(compliancePerson, creditorA, CreditorRole.COMPLIANCE.permissions(),
                () -> portal.activity(true, null, 50));
        assertThat(team.scope()).isEqualTo("TEAM");
        assertThat(team.items()).extracting(ActivityItem::personName).containsOnly("Ana do Banco", "Bia do Banco");
        assertThat(team.items()).extracting(ActivityItem::action).containsExactlyInAnyOrder("STATEMENT_ACKNOWLEDGED",
                "PAYMENT_REPORTED", "SIGNED_IN", "FILE_DOWNLOADED", "DISPUTE_RESPONDED");
        assertThat(team.items()).noneMatch(ActivityItem::mine);
        assertThat(count("select count(*) from shared.access_log where event_type = 'USER_DIRECTORY_QUERIED' "
                + "and tenant_id = ? and details ->> 'scope' = 'creditor_team' and details ->> 'origin' = 'creditor_portal'"))
                .isEqualTo(1);
        // o credor B só vê o Caio
        assertThat(as(Ids.newId(), creditorB, CreditorRole.GESTOR.permissions(), () -> portal.activity(true, null, 50))
                .items()).extracting(ActivityItem::personName).containsOnly("Caio da Loja");
        // ator de outra assessoria com o id do credor A (e até com o id da Ana): o RLS da assessoria não deixa ver nada
        String otherSlug = "act2-" + UUID.randomUUID().toString().substring(0, 8);
        UUID otherTenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> provisioning.provision(
                new ProvisionTenantCommand(otherSlug, "Outra Assessoria Ltda", "Outra", "45.723.174/0001-10",
                        "Admin", "admin@" + otherSlug + ".test"))).tenant().id();
        for (UUID person : List.of(ana, Ids.newId())) {
            Actor outsider = Actor.creditor(otherTenant, person, Ids.newId(), creditorA,
                    CreditorRole.GESTOR.permissions());
            assertThat(tenantExecution.callAs(outsider, () -> portal.activity(false, null, 50)).items()).isEmpty();
            assertThat(tenantExecution.callAs(outsider, () -> portal.activity(true, null, 50)).items()).isEmpty();
        }
        // a porta do credor não atende a assessoria
        assertThatThrownBy(() -> tenantExecution.callAs(agency, () -> activity.activity(false, null, 10)))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // ---- HTTP: no-store, parâmetros fechados, nada da assessoria nem e-mail, contrato por snapshot
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ActorAuthentication auth = ActorAuthentication.of(Actor.creditor(tenant, Ids.newId(), Ids.newId(), creditorA,
                CreditorRole.COMPLIANCE.permissions()));
        String body = mvc.perform(get("/api/creditor/v1/activity").param("scope", "team").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(AGENCY_NAME).doesNotContain("@banco-a.test").doesNotContain("Caio")
                .doesNotContain("creditor:").doesNotContain(bia.toString());
        JsonContract.assertMatches(json.readTree(body), "creditor-portal/activity.txt");
        for (String[] bad : new String[][]{{"scope", "everyone"}, {"size", "51"}, {"size", "0"}, {"before", "não/é/cursor"}}) {
            mvc.perform(get("/api/creditor/v1/activity").param(bad[0], bad[1]).with(authentication(auth)))
                    .andExpect(status().isBadRequest());
        }
        ActorAuthentication reader = ActorAuthentication.of(Actor.creditor(tenant, Ids.newId(), Ids.newId(), creditorA,
                CreditorRole.LEITURA.permissions()));
        mvc.perform(get("/api/creditor/v1/activity").param("scope", "team").with(authentication(reader)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ apoio

    private UUID creditorUser(UUID creditorId, String name, String email, String role, String status) {
        UUID id = Ids.newId();
        Timestamp now = Timestamp.from(Instant.now());
        system(() -> jdbcTemplate.update("""
                insert into identity.creditor_user (id, tenant_id, version, created_at, updated_at, creditor_id, name,
                    email, role, status, invited_by, invited_at, revoked_at, revoked_by)
                values (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, tenant, now, now, creditorId, name, email, role, status, Ids.newId(), now,
                status.equals("REVOKED") ? now : null, status.equals("REVOKED") ? Ids.newId() : null));
        return id;
    }

    private Actor creditor(UUID person, UUID creditorId) {
        return Actor.creditor(tenant, person, Ids.newId(), creditorId, Set.of(Permission.CREDITOR_PORTAL_OVERVIEW));
    }

    private void audit(Actor actor, String action, String entityType, UUID entityId, UUID creditorId) {
        tenantExecution.callAs(actor, () -> {
            auditTrail.record(AuditEntry.of(action, entityType, entityId).creditor(creditorId)
                    .reason("motivo interno que nunca vai ao portal").meta("ip", "203.0.113.9"));
            return null;
        });
    }

    private void access(AccessEventType type, UUID person, Map<String, String> details) {
        system(() -> {
            accessLogger.log(AccessEvent.forCreditor(type, tenant, person, Ids.newId(), "a***@banco-a.test", details));
            return null;
        });
    }

    private <T> T as(UUID person, UUID creditorId, Set<Permission> permissions, Supplier<T> action) {
        return tenantExecution.callAs(Actor.creditor(tenant, person, Ids.newId(), creditorId, permissions), action);
    }

    private <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    private long count(String sql) {
        return system(() -> {
            Long value = jdbcTemplate.queryForObject(sql, Long.class, tenant);
            return value == null ? 0L : value;
        });
    }
}
