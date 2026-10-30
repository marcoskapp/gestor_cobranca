package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.publicapi.application.port.out.UsageDelta;
import br.com.gestorcobranca.publicapi.application.port.out.UsageStore.LastUse;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Acumulação em memória do consumo, por (assessoria, credor, token, hora, método, rota): cada requisição só soma
 * contadores ({@link LongAdder}, sem bloqueio entre threads). O {@code UsageFlushJob} drena o acumulado e grava em
 * lote, por soma, a cada minuto e no desligamento da instância. Em queda abrupta da instância perde-se no máximo o
 * último intervalo (o log de acesso por requisição continua completo e permite reconstituir o consumo).
 */
@Component
class UsageAccumulator {

    record Key(UUID tenantId, UUID creditorId, UUID tokenId, Instant hourStart, String method, String route) {
    }

    static final class Counters {
        final LongAdder requests = new LongAdder();
        final LongAdder throttled = new LongAdder();
        final LongAdder clientErrors = new LongAdder();
        final LongAdder serverErrors = new LongAdder();
        final LongAdder bytesIn = new LongAdder();
        final LongAdder bytesOut = new LongAdder();
        final LongAdder records = new LongAdder();
        final LongAdder latencyMs = new LongAdder();
    }

    private volatile Map<Key, Counters> current = new ConcurrentHashMap<>();
    private volatile Map<UUID, LastUseHolder> lastUses = new ConcurrentHashMap<>();

    private record LastUseHolder(UUID tenantId, Instant at, String ip) {
    }

    /** @param status 0 para registros processados de forma assíncrona (não conta requisição) */
    void add(UUID tenantId, UUID creditorId, UUID tokenId, Instant at, String method, String route, int status,
             long bytesIn, long bytesOut, long records, long latencyMs) {
        Key key = new Key(tenantId, creditorId, tokenId, at.truncatedTo(ChronoUnit.HOURS), method, route);
        Counters c = current.computeIfAbsent(key, k -> new Counters());
        if (status > 0) {
            c.requests.increment();
            if (status == 429) {
                c.throttled.increment();
            } else if (status >= 500) {
                c.serverErrors.increment();
            } else if (status >= 400) {
                c.clientErrors.increment();
            }
        }
        c.bytesIn.add(bytesIn);
        c.bytesOut.add(bytesOut);
        c.records.add(records);
        c.latencyMs.add(latencyMs);
    }

    /** Devolve um delta não gravado (falha na gravação) para a próxima tentativa. */
    void restore(UsageDelta d) {
        Counters c = current.computeIfAbsent(new Key(d.tenantId(), d.creditorId(), d.tokenId(), d.hourStart(), d.method(),
                d.route()), k -> new Counters());
        c.requests.add(d.requests());
        c.throttled.add(d.throttled());
        c.clientErrors.add(d.clientErrors());
        c.serverErrors.add(d.serverErrors());
        c.bytesIn.add(d.bytesIn());
        c.bytesOut.add(d.bytesOut());
        c.records.add(d.records());
        c.latencyMs.add(d.latencyMsTotal());
    }

    void touch(UUID tenantId, UUID tokenId, Instant at, String ip) {
        lastUses.merge(tokenId, new LastUseHolder(tenantId, at, ip), (a, b) -> b.at().isAfter(a.at()) ? b : a);
    }

    /**
     * Mapa aposentado na troca anterior. Uma requisição que obteve os contadores do mapa corrente logo antes da troca
     * ainda pode somar neles por alguns instantes; por isso o mapa só é lido no intervalo seguinte, já sem escritores.
     */
    private volatile Map<Key, Counters> retired = new ConcurrentHashMap<>();

    /**
     * Drena o mapa aposentado na troca anterior (já sem escritores em andamento) e aposenta o corrente. Assim nenhum
     * incremento se perde entre a leitura e a troca: o atraso é de um intervalo de gravação.
     */
    synchronized Drained drain() {
        Map<Key, Counters> toWrite = retired;
        retired = current;
        current = new ConcurrentHashMap<>();
        Map<UUID, LastUseHolder> uses = lastUses;
        lastUses = new ConcurrentHashMap<>();
        return new Drained(toDeltas(toWrite), toLastUses(uses));
    }

    /** Desligamento: drena tudo (aposentado e corrente). */
    synchronized Drained drainAll() {
        Drained first = drain();
        Drained second = drain();
        List<UsageDelta> deltas = new ArrayList<>(first.deltas());
        deltas.addAll(second.deltas());
        return new Drained(deltas, first.lastUses());
    }

    int pendingKeys() {
        return current.size() + retired.size();
    }

    record Drained(List<UsageDelta> deltas, Map<UUID, List<LastUse>> lastUses) {
    }

    private static List<UsageDelta> toDeltas(Map<Key, Counters> map) {
        List<UsageDelta> deltas = new ArrayList<>(map.size());
        map.forEach((k, c) -> deltas.add(new UsageDelta(k.tenantId(), k.creditorId(), k.tokenId(), k.hourStart(),
                k.method(), k.route(), c.requests.sum(), c.throttled.sum(), c.clientErrors.sum(), c.serverErrors.sum(),
                c.bytesIn.sum(), c.bytesOut.sum(), c.records.sum(), c.latencyMs.sum())));
        return deltas;
    }

    private static Map<UUID, List<LastUse>> toLastUses(Map<UUID, LastUseHolder> uses) {
        Map<UUID, List<LastUse>> byTenant = new java.util.HashMap<>();
        uses.forEach((tokenId, h) -> byTenant.computeIfAbsent(h.tenantId(), t -> new ArrayList<>())
                .add(new LastUse(tokenId, h.at(), h.ip())));
        return byTenant;
    }
}
