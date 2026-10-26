package br.com.gestorcobranca.pendingwork.application.service;

import br.com.gestorcobranca.pendingwork.application.PendingWorkProperties;
import br.com.gestorcobranca.pendingwork.application.port.in.PendingWorkUseCases;
import br.com.gestorcobranca.pendingwork.application.port.out.SourceStatementTimeout;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkItem;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRequest;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkSource;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agregador: consulta em paralelo as fontes que se aplicam às permissões do usuário, cada uma em
 * transação somente leitura própria e com {@code statement_timeout}, e espera no máximo
 * {@code app.pending-work.timeout} pelo conjunto. Fonte que falha, estoura o tempo ou não cabe na fila
 * do executor é marcada em {@code unavailable}; as demais seguem na resposta.
 * <p>
 * Sem cache no servidor: as contagens são consultas indexadas e a interface compartilha uma única
 * consulta por aba (indicador da barra superior e tela inicial), com atualização a cada 60 s.
 */
@Service
class PendingWorkService implements PendingWorkUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(PendingWorkService.class);

    static final Comparator<ItemView> ORDER = Comparator
            .comparing((ItemView i) -> severityRank(i.severity()))
            .thenComparing(ItemView::nextDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ItemView::oldestAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ItemView::type);

    private final List<PendingWorkSource> sources;
    private final CurrentActor currentActor;
    private final TenantExecution tenantExecution;
    private final SourceStatementTimeout statementTimeout;
    private final AsyncTaskExecutor executor;
    private final PendingWorkObservations observations;
    private final PendingWorkMetrics metrics;
    private final PendingWorkProperties properties;
    private final Clock clock;

    PendingWorkService(List<PendingWorkSource> sources, CurrentActor currentActor, TenantExecution tenantExecution,
                       SourceStatementTimeout statementTimeout,
                       @Qualifier("pendingWorkExecutor") AsyncTaskExecutor executor, PendingWorkObservations observations,
                       PendingWorkMetrics metrics, PendingWorkProperties properties, Clock clock) {
        this.sources = List.copyOf(sources);
        this.currentActor = currentActor;
        this.tenantExecution = tenantExecution;
        this.statementTimeout = statementTimeout;
        this.executor = executor;
        this.observations = observations;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public PendingWorkSummary mine() {
        long started = System.nanoTime();
        Actor actor = currentActor.get();
        PendingWorkRequest request = request(actor, Instant.now(clock), properties.dueSoonDays());
        Map<PendingWorkSource, Future<List<PendingWorkItem>>> running = new LinkedHashMap<>();
        List<UnavailableSource> unavailable = new ArrayList<>();
        List<PendingWorkSource> applicable = sources.stream().filter(s -> s.appliesTo(actor)).toList();
        for (PendingWorkSource source : applicable) {
            try {
                running.put(source, executor.submit(() -> run(source, request)));
            } catch (TaskRejectedException e) {
                metrics.source(source.id(), "busy", Duration.ZERO);
                LOG.atWarn().addKeyValue("source", source.id()).log("Fonte de pendências recusada: executor saturado");
                unavailable.add(new UnavailableSource(source.id(), source.label(), "BUSY"));
            }
        }
        long deadline = started + properties.timeout().toNanos();
        List<ItemView> items = new ArrayList<>();
        for (Map.Entry<PendingWorkSource, Future<List<PendingWorkItem>>> entry : running.entrySet()) {
            PendingWorkSource source = entry.getKey();
            Future<List<PendingWorkItem>> future = entry.getValue();
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                for (PendingWorkItem item : future.get(remaining, TimeUnit.NANOSECONDS)) {
                    if (item.count().total() > 0) {
                        items.add(view(source, item));
                    }
                }
            } catch (TimeoutException e) {
                future.cancel(true);
                metrics.source(source.id(), "timeout", properties.timeout());
                LOG.atWarn().addKeyValue("source", source.id()).addKeyValue("timeoutMs", properties.timeout().toMillis())
                        .log("Fonte de pendências excedeu o tempo limite");
                unavailable.add(new UnavailableSource(source.id(), source.label(), "TIMEOUT"));
            } catch (ExecutionException e) {
                unavailable.add(new UnavailableSource(source.id(), source.label(), "ERROR"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                unavailable.add(new UnavailableSource(source.id(), source.label(), "ERROR"));
            }
        }
        items.sort(ORDER);
        unavailable.sort(Comparator.comparing(UnavailableSource::source));
        observations.observe(actor, request.today(), request.now(), items);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        metrics.request(unavailable.isEmpty() ? "complete" : "partial", elapsed, applicable.size());
        return new PendingWorkSummary(request.now(), items.stream().mapToLong(ItemView::count).sum(),
                items.stream().mapToLong(ItemView::overdue).sum(), items.stream().mapToLong(ItemView::dueToday).sum(),
                List.copyOf(items), List.copyOf(unavailable));
    }

    static PendingWorkRequest request(Actor actor, Instant now, int dueSoonDays) {
        ZonedDateTime local = now.atZone(TimeConfig.BUSINESS_ZONE);
        LocalDate today = local.toLocalDate();
        return new PendingWorkRequest(actor, now, today, today.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant(),
                today.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant(), dueSoonDays);
    }

    private List<PendingWorkItem> run(PendingWorkSource source, PendingWorkRequest request) {
        long started = System.nanoTime();
        try {
            List<PendingWorkItem> result = tenantExecution.callAsReadOnly(request.actor(), () -> {
                statementTimeout.apply(properties.timeout());
                return source.pendingFor(request);
            });
            metrics.source(source.id(), "ok", Duration.ofNanos(System.nanoTime() - started));
            return result == null ? List.of() : result;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                // cancelada pelo agregador após o tempo limite (já contada como timeout)
                throw e;
            }
            metrics.source(source.id(), "error", Duration.ofNanos(System.nanoTime() - started));
            LOG.atWarn().addKeyValue("source", source.id()).addKeyValue("error", e.getClass().getSimpleName())
                    .setCause(e).log("Fonte de pendências falhou");
            throw e;
        }
    }

    private static ItemView view(PendingWorkSource source, PendingWorkItem item) {
        var c = item.count();
        return new ItemView(item.type(), source.id(), source.label(), item.label(), item.hint(), c.total(), c.overdue(),
                c.dueToday(), item.severity().name(), c.oldestAt(), c.nextDueDate(), item.route(), item.queryParams());
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "OVERDUE" -> 0;
            case "DUE_TODAY" -> 1;
            default -> 2;
        };
    }
}
