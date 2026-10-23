package br.com.gestorcobranca.identity;

import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases;
import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases.ChecklistUpdate;
import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases.ScreenUpdate;
import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases.TourUpdate;
import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases.UiStatePatch;
import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases.UiStateView;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Estado de interface (onboarding e preferências) contra PostgreSQL real: persistência entre
 * "dispositivos", isolamento entre usuários e assessorias (RLS), criação concorrente e acesso
 * anônimo negado.
 */
class UiStateIT extends IntegrationTest {

    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantAdministration tenantAdministration;
    @Autowired
    UserRepository users;
    @Autowired
    UiStateUseCases uiState;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @LocalServerPort
    int port;

    UUID tenant;
    UUID otherTenant;
    UUID ana;
    UUID bruno;
    UUID carla;

    @BeforeEach
    void setUp() {
        tenant = createTenant("ui");
        otherTenant = createTenant("ux");
        ana = createUser(tenant, "ana");
        bruno = createUser(tenant, "bruno");
        carla = createUser(otherTenant, "carla");
    }

    private UUID createTenant(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, prefix + " Ltda", prefix, "11.444.777/0001-61")).id());
    }

    private UUID createUser(UUID tenantId, String name) {
        return tenantExecution.callInTenant(tenantId, "test.setup",
                () -> users.save(new User(name, name + "@teste.com.br", null)).getId());
    }

    private <T> T as(UUID tenantId, UUID userId, Supplier<T> action) {
        Actor actor = new Actor(Actor.ActorType.USER, userId, tenantId, Ids.newId(), "usuario", Set.of(),
                CreditorScope.NONE);
        return tenantExecution.callAs(actor, action);
    }

    private long rowsVisibleIn(UUID tenantId, String table) {
        return tenantExecution.callInTenant(tenantId, "test",
                () -> jdbcTemplate.queryForObject("select count(*) from identity." + table, Long.class));
    }

    @Test
    void progressFollowsTheUserAcrossSessions() {
        as(tenant, ana, () -> uiState.updateMyState(new UiStatePatch("DARK", "COMPACT", true, null, null, null,
                List.of(new TourUpdate("shell.intro", 1, "COMPLETED")),
                List.of(new ScreenUpdate("admin.users", 2)),
                List.of(new ChecklistUpdate("admin.create-creditor", true)))));

        // outra sessão (outro dispositivo) do mesmo usuário
        UiStateView view = as(tenant, ana, uiState::myState);
        assertThat(view.theme()).isEqualTo("DARK");
        assertThat(view.density()).isEqualTo("COMPACT");
        assertThat(view.navigationCollapsed()).isTrue();
        assertThat(view.tours().get("shell.intro").version()).isEqualTo(1);
        assertThat(view.screens().get("admin.users").version()).isEqualTo(2);
        assertThat(view.checklist()).containsKey("admin.create-creditor");

        // uma versão antiga vinda de uma aba desatualizada não regride o estado
        UiStateView after = as(tenant, ana, () -> uiState.updateMyState(new UiStatePatch(null, null, null, null, null, null, null,
                List.of(new ScreenUpdate("admin.users", 1)), null)));
        assertThat(after.screens().get("admin.users").version()).isEqualTo(2);
    }

    @Test
    void eachUserSeesOnlyTheirOwnState() {
        as(tenant, ana, () -> uiState.updateMyState(new UiStatePatch("DARK", null, null, null, null, null,
                List.of(new TourUpdate("shell.intro", 1, "DISMISSED")), null, null)));

        UiStateView brunoView = as(tenant, bruno, uiState::myState);
        assertThat(brunoView.theme()).isEqualTo("SYSTEM");
        assertThat(brunoView.tours()).isEmpty();

        // RLS: a outra assessoria não enxerga linhas da primeira, nem nas tabelas filhas
        assertThat(rowsVisibleIn(tenant, "user_ui_state")).isEqualTo(1);
        assertThat(rowsVisibleIn(otherTenant, "user_ui_state")).isZero();
        assertThat(rowsVisibleIn(otherTenant, "user_ui_tour")).isZero();
        assertThat(as(otherTenant, carla, uiState::myState).tours()).isEmpty();
    }

    @Test
    void rejectsInvalidIdentifiersWithoutPartialWrites() {
        assertThatThrownBy(() -> as(tenant, ana, () -> uiState.updateMyState(new UiStatePatch("DARK", null, null, null, null, null,
                List.of(new TourUpdate("Tour Inválido", 1, "COMPLETED")), null, null))))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(as(tenant, ana, uiState::myState).theme()).isEqualTo("SYSTEM");
    }

    @Test
    void concurrentFirstUpdatesOfTheSameUserAreSerialized() throws Exception {
        int parallel = 6;
        ExecutorService executor = Executors.newFixedThreadPool(parallel);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UiStateView>> results = new ArrayList<>();
        try {
            for (int i = 0; i < parallel; i++) {
                String screen = "screen-" + i;
                results.add(executor.submit(() -> {
                    start.await();
                    return as(tenant, bruno, () -> uiState.updateMyState(new UiStatePatch(null, null, null, null, null, null, null,
                            List.of(new ScreenUpdate(screen, 1)), null)));
                }));
            }
            start.countDown();
            for (Future<UiStateView> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(as(tenant, bruno, uiState::myState).screens()).hasSize(parallel);
        assertThat(rowsVisibleIn(tenant, "user_ui_state")).isEqualTo(1);
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> get = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/me/ui-state"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> patch = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/me/ui-state"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"theme\":\"DARK\"}")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(get.statusCode()).isEqualTo(401);
        assertThat(patch.statusCode()).isIn(401, 403);
    }
}
