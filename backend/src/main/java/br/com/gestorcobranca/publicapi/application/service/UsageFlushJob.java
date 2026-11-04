package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.publicapi.application.port.out.RejectedDelta;
import br.com.gestorcobranca.publicapi.application.port.out.UsageDelta;
import br.com.gestorcobranca.publicapi.application.port.out.UsageStore;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Gravação em lote do consumo acumulado em memória: a cada {@code app.public-api.usage-flush-interval} (padrão 1 min)
 * e no desligamento da instância. Roda em <strong>todas</strong> as instâncias (sem ShedLock): cada uma grava o que
 * acumulou, por soma ({@code upsert}), uma transação por assessoria. Falha numa assessoria devolve os deltas ao
 * acumulador para a próxima tentativa.
 */
@Component
class UsageFlushJob {

    private static final Logger LOG = LoggerFactory.getLogger(UsageFlushJob.class);

    private final UsageAccumulator accumulator;
    private final UsageStore store;
    private final TenantExecution tenantExecution;
    private final MeterRegistry meterRegistry;

    UsageFlushJob(UsageAccumulator accumulator, UsageStore store, TenantExecution tenantExecution,
                  MeterRegistry meterRegistry) {
        this.accumulator = accumulator;
        this.store = store;
        this.tenantExecution = tenantExecution;
        this.meterRegistry = meterRegistry;
        Gauge.builder("publicapi.usage.pending_keys", accumulator, UsageAccumulator::pendingKeys).register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.public-api.usage-flush-interval:PT1M}",
            initialDelayString = "${app.public-api.usage-flush-interval:PT1M}")
    void flush() {
        write(accumulator.drain());
    }

    @PreDestroy
    void flushOnShutdown() {
        write(accumulator.drainAll());
    }

    /** Grava um lote drenado (consumo e tráfego recusado), uma transação por assessoria. Visível ao pacote para testes. */
    void write(UsageAccumulator.Drained drained) {
        Map<UUID, List<UsageDelta>> byTenant = drained.deltas().stream()
                .collect(Collectors.groupingBy(UsageDelta::tenantId));
        Map<UUID, List<RejectedDelta>> rejectedByTenant = drained.rejected().stream()
                .collect(Collectors.groupingBy(RejectedDelta::tenantId));
        Set<UUID> tenants = new LinkedHashSet<>(byTenant.keySet());
        tenants.addAll(rejectedByTenant.keySet());
        for (UUID tenantId : tenants) {
            List<UsageDelta> deltas = byTenant.getOrDefault(tenantId, List.of());
            List<RejectedDelta> rejected = rejectedByTenant.getOrDefault(tenantId, List.of());
            int rows = deltas.size() + rejected.size();
            long started = System.nanoTime();
            try {
                tenantExecution.inTenant(tenantId, "publicapi.usage_flush", () -> {
                    store.add(deltas);
                    store.addRejected(rejected);
                    store.touchTokens(drained.lastUses().getOrDefault(tenantId, List.of()));
                });
                meterRegistry.counter("publicapi.usage.flushed_rows", "outcome", "ok").increment(rows);
            } catch (RuntimeException ex) {
                meterRegistry.counter("publicapi.usage.flushed_rows", "outcome", "failed").increment(rows);
                LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("rows", rows)
                        .setCause(ex).log("Falha ao gravar o consumo da API pública; devolvido ao acumulador");
                deltas.forEach(accumulator::restore);
                rejected.forEach(accumulator::restore);
            } finally {
                meterRegistry.timer("publicapi.usage.flush.duration").record(System.nanoTime() - started,
                        java.util.concurrent.TimeUnit.NANOSECONDS);
            }
        }
    }
}
