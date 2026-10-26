package br.com.gestorcobranca.pendingwork.application.service;

import br.com.gestorcobranca.pendingwork.application.PendingWorkProperties;
import br.com.gestorcobranca.pendingwork.application.port.in.PendingWorkUseCases.ItemView;
import br.com.gestorcobranca.pendingwork.application.port.in.PendingWorkUseCases.PendingWorkSummary;
import br.com.gestorcobranca.pendingwork.application.port.in.PendingWorkUseCases.UnavailableSource;
import br.com.gestorcobranca.pendingwork.application.port.out.SourceStatementTimeout;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkItem;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRequest;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkSource;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agregador: fontes em paralelo, falha e tempo limite isolados (resposta parcial marcada), fontes que
 * não se aplicam às permissões não são consultadas, itens zerados descartados e ordenação por
 * severidade, prazo e idade.
 */
class PendingWorkServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T15:00:00Z");
    private final Actor actor = new Actor(Actor.ActorType.USER, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "Usuária", Set.of(Permission.PAYMENT_REVIEW), CreditorScope.ALL);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<String> timeouts = new CopyOnWriteArrayList<>();
    private ThreadPoolTaskExecutor executor;
    private final List<List<ItemView>> observed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.initialize();
        SecurityContextHolder.getContext().setAuthentication(ActorAuthentication.of(actor));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        SecurityContextHolder.clearContext();
    }

    private PendingWorkService service(Duration timeout, PendingWorkSource... sources) {
        TenantExecution tenantExecution = mock(TenantExecution.class);
        when(tenantExecution.callAsReadOnly(any(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        SourceStatementTimeout statementTimeout = t -> timeouts.add(t.toString());
        PendingWorkObservations observations = mock(PendingWorkObservations.class);
        org.mockito.Mockito.doAnswer(inv -> {
            observed.add(List.copyOf(inv.getArgument(3)));
            return null;
        }).when(observations).observe(any(), any(), any(), any());
        return new PendingWorkService(List.of(sources), new CurrentActor(), tenantExecution, statementTimeout, executor,
                observations, new PendingWorkMetrics(meters), new PendingWorkProperties(timeout, 4, 10, 3, 30),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void failingAndSlowSourcesDoNotBreakTheOthers() {
        PendingWorkSource ok = source("payment", true, r -> List.of(item("payment.external_review",
                new PendingWorkCount(2, 0, 0, NOW.minusSeconds(3600), null, null))));
        PendingWorkSource broken = source("settlement", true, r -> {
            throw new IllegalStateException("banco indisponível");
        });
        PendingWorkSource slow = source("legal", true, r -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of(item("legal.deadlines_due", PendingWorkCount.of(1, NOW)));
        });

        long started = System.nanoTime();
        PendingWorkSummary summary = service(Duration.ofMillis(300), ok, broken, slow).mine();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(summary.items()).extracting(ItemView::type).containsExactly("payment.external_review");
        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.unavailable()).extracting(UnavailableSource::source, UnavailableSource::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("legal", "TIMEOUT"),
                        org.assertj.core.groups.Tuple.tuple("settlement", "ERROR"));
        assertThat(timeouts).contains("PT0.3S");
        assertThat(meters.get("pendingwork.request.duration").tag("outcome", "partial").timer().count()).isEqualTo(1);
        assertThat(meters.get("pendingwork.source.duration").tag("source", "settlement").tag("outcome", "error").timer()
                .count()).isEqualTo(1);
    }

    @Test
    void sourcesOutsideThePermissionsAreNotQueriedAndEmptyItemsAreDropped() {
        List<String> called = new CopyOnWriteArrayList<>();
        PendingWorkSource notMine = source("scoring", false, r -> {
            called.add("scoring");
            return List.of(item("scoring.challenger_decision", PendingWorkCount.of(1, NOW)));
        });
        PendingWorkSource empty = source("payment", true, r -> {
            called.add("payment");
            return List.of(item("payment.external_review", PendingWorkCount.NONE));
        });

        PendingWorkSummary summary = service(Duration.ofSeconds(2), notMine, empty).mine();

        assertThat(called).containsExactly("payment");
        assertThat(summary.items()).isEmpty();
        assertThat(summary.unavailable()).isEmpty();
        assertThat(summary.total()).isZero();
    }

    @Test
    void ordersOverdueFirstThenDueTodayThenByDeadlineAndAgeAndPassesTheBusinessDay() {
        List<PendingWorkRequest> requests = new CopyOnWriteArrayList<>();
        PendingWorkSource many = source("collection", true, r -> {
            requests.add(r);
            return List.of(
                    item("collection.normal_old", PendingWorkCount.of(1, NOW.minus(Duration.ofDays(9)))),
                    item("collection.normal_new", PendingWorkCount.of(5, NOW.minus(Duration.ofHours(1)))),
                    item("collection.today", new PendingWorkCount(2, 0, 1, NOW, LocalDate.of(2026, 10, 26), null)),
                    item("collection.overdue", new PendingWorkCount(3, 2, 0, NOW, LocalDate.of(2026, 10, 20), null)));
        });

        PendingWorkSummary summary = service(Duration.ofSeconds(2), many).mine();

        assertThat(summary.items()).extracting(ItemView::type).containsExactly("collection.overdue", "collection.today",
                "collection.normal_old", "collection.normal_new");
        assertThat(summary.items()).extracting(ItemView::severity).containsExactly("OVERDUE", "DUE_TODAY", "NORMAL",
                "NORMAL");
        assertThat(summary.overdue()).isEqualTo(2);
        assertThat(summary.dueToday()).isEqualTo(1);
        assertThat(summary.items().getFirst().area()).isEqualTo("Área collection");
        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r.today()).isEqualTo(LocalDate.of(2026, 10, 26));
            assertThat(r.startOfToday()).isEqualTo(Instant.parse("2026-10-26T03:00:00Z"));
            assertThat(r.startOfTomorrow()).isEqualTo(Instant.parse("2026-10-27T03:00:00Z"));
            assertThat(r.dueSoonUntil()).isEqualTo(LocalDate.of(2026, 10, 29));
        });
        assertThat(observed).singleElement().satisfies(items -> assertThat(items).hasSize(4));
    }

    @Test
    void ageBandsForTheBusinessLog() {
        assertThat(PendingWorkObservations.ageBand(null)).isNull();
        assertThat(PendingWorkObservations.ageBand(3L)).isEqualTo("<1d");
        assertThat(PendingWorkObservations.ageBand(30L)).isEqualTo("1-3d");
        assertThat(PendingWorkObservations.ageBand(100L)).isEqualTo("3-7d");
        assertThat(PendingWorkObservations.ageBand(200L)).isEqualTo("7-30d");
        assertThat(PendingWorkObservations.ageBand(800L)).isEqualTo("30d+");
    }

    private static PendingWorkItem item(String type, PendingWorkCount count) {
        return PendingWorkItem.of(type, "Rótulo " + type, count).route("/destino").build();
    }

    private static PendingWorkSource source(String id, boolean applies,
                                            Function<PendingWorkRequest, List<PendingWorkItem>> body) {
        return new PendingWorkSource() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return "Área " + id;
            }

            @Override
            public boolean appliesTo(Actor a) {
                return applies;
            }

            @Override
            public List<PendingWorkItem> pendingFor(PendingWorkRequest request) {
                return body.apply(request);
            }
        };
    }
}
