package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.publicapi.application.port.out.RejectedDelta;
import br.com.gestorcobranca.publicapi.application.port.out.UsageDelta;
import br.com.gestorcobranca.publicapi.application.port.out.UsageStore.LastUse;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Acumulação em memória do consumo, por (assessoria, credor, token, hora, método, rota): cada requisição só soma
 * contadores ({@link LongAdder}) sob uma trava de leitura compartilhada — sem contenção entre requisições. O
 * {@code UsageFlushJob} troca o mapa sob a trava de escrita e grava o acumulado em lote, por soma, a cada minuto e no
 * desligamento da instância. Em queda abrupta da instância perde-se no máximo o
 * último intervalo (o log de acesso por requisição continua completo e permite reconstituir o consumo).
 * <p>
 * As chamadas recusadas na autenticação de um token identificado somam num mapa próprio (tráfego recusado), drenado e
 * gravado junto, em tabela separada — nunca entram no consumo cobrável.
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
        final LongAdder billableBytesIn = new LongAdder();
        final LongAdder billableBytesOut = new LongAdder();
        final LongAdder records = new LongAdder();
        final LongAdder latencyMs = new LongAdder();
    }

    record RejectedKey(UUID tenantId, UUID creditorId, UUID tokenId, Instant hourStart, String reason, String method) {
    }

    /** Métodos gravados como estão; qualquer outro vira {@code OTHER} (coluna curta, sem texto livre do cliente). */
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    /** Requisições somam sob a trava de leitura (compartilhada); a troca do mapa usa a de escrita. */
    private final ReentrantReadWriteLock swap = new ReentrantReadWriteLock();
    private Map<Key, Counters> current = new ConcurrentHashMap<>();
    private Map<RejectedKey, LongAdder> rejected = new ConcurrentHashMap<>();
    private Map<UUID, LastUseHolder> lastUses = new ConcurrentHashMap<>();

    private record LastUseHolder(UUID tenantId, Instant at, String ip) {
    }

    /** @param status 0 para registros processados de forma assíncrona (não conta requisição) */
    void add(UUID tenantId, UUID creditorId, UUID tokenId, Instant at, String method, String route, int status,
             long bytesIn, long bytesOut, long records, long latencyMs) {
        Key key = new Key(tenantId, creditorId, tokenId, at.truncatedTo(ChronoUnit.HOURS), method, route);
        swap.readLock().lock();
        try {
            increment(current.computeIfAbsent(key, k -> new Counters()), status, bytesIn, bytesOut, records, latencyMs);
        } finally {
            swap.readLock().unlock();
        }
    }

    private static void increment(Counters c, int status, long bytesIn, long bytesOut, long records, long latencyMs) {
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
        if (billable(status)) {
            c.billableBytesIn.add(bytesIn);
            c.billableBytesOut.add(bytesOut);
        }
        c.records.add(records);
        c.latencyMs.add(latencyMs);
    }

    /** Requisição cobrável: resposta 2xx/3xx (decisão 607). Registros assíncronos (status 0) não têm tráfego. */
    static boolean billable(int status) {
        return status >= 200 && status < 400;
    }

    /** Recusa na autenticação de um token identificado (tráfego recusado, não cobrado). */
    void addRejected(UUID tenantId, UUID creditorId, UUID tokenId, Instant at, String reason, String method) {
        String m = method == null ? "OTHER" : method.toUpperCase(Locale.ROOT);
        RejectedKey key = new RejectedKey(tenantId, creditorId, tokenId, at.truncatedTo(ChronoUnit.HOURS), reason,
                METHODS.contains(m) ? m : "OTHER");
        swap.readLock().lock();
        try {
            rejected.computeIfAbsent(key, k -> new LongAdder()).increment();
        } finally {
            swap.readLock().unlock();
        }
    }

    /** Devolve um delta de recusas não gravado para a próxima tentativa. */
    void restore(RejectedDelta d) {
        swap.readLock().lock();
        try {
            rejected.computeIfAbsent(new RejectedKey(d.tenantId(), d.creditorId(), d.tokenId(), d.hourStart(),
                    d.reason(), d.method()), k -> new LongAdder()).add(d.attempts());
        } finally {
            swap.readLock().unlock();
        }
    }

    /** Devolve um delta não gravado (falha na gravação) para a próxima tentativa. */
    void restore(UsageDelta d) {
        swap.readLock().lock();
        try {
            Counters c = current.computeIfAbsent(new Key(d.tenantId(), d.creditorId(), d.tokenId(), d.hourStart(),
                    d.method(), d.route()), k -> new Counters());
            c.requests.add(d.requests());
            c.throttled.add(d.throttled());
            c.clientErrors.add(d.clientErrors());
            c.serverErrors.add(d.serverErrors());
            c.bytesIn.add(d.bytesIn());
            c.bytesOut.add(d.bytesOut());
            c.billableBytesIn.add(d.billableBytesIn());
            c.billableBytesOut.add(d.billableBytesOut());
            c.records.add(d.records());
            c.latencyMs.add(d.latencyMsTotal());
        } finally {
            swap.readLock().unlock();
        }
    }

    void touch(UUID tenantId, UUID tokenId, Instant at, String ip) {
        swap.readLock().lock();
        try {
            lastUses.merge(tokenId, new LastUseHolder(tenantId, at, ip), (a, b) -> b.at().isAfter(a.at()) ? b : a);
        } finally {
            swap.readLock().unlock();
        }
    }

    /**
     * Troca os mapas sob a trava de escrita (espera as somas em andamento) e devolve o acumulado: nenhum incremento se
     * perde entre a leitura e a troca.
     */
    Drained drain() {
        Map<Key, Counters> toWrite;
        Map<UUID, LastUseHolder> uses;
        Map<RejectedKey, LongAdder> refused;
        swap.writeLock().lock();
        try {
            toWrite = current;
            uses = lastUses;
            refused = rejected;
            current = new ConcurrentHashMap<>();
            lastUses = new ConcurrentHashMap<>();
            rejected = new ConcurrentHashMap<>();
        } finally {
            swap.writeLock().unlock();
        }
        return new Drained(toDeltas(toWrite), toLastUses(uses), toRejected(refused));
    }

    /** Desligamento: drena tudo. */
    Drained drainAll() {
        return drain();
    }

    int pendingKeys() {
        swap.readLock().lock();
        try {
            return current.size() + rejected.size();
        } finally {
            swap.readLock().unlock();
        }
    }

    record Drained(List<UsageDelta> deltas, Map<UUID, List<LastUse>> lastUses, List<RejectedDelta> rejected) {
    }

    private static List<RejectedDelta> toRejected(Map<RejectedKey, LongAdder> map) {
        List<RejectedDelta> deltas = new ArrayList<>(map.size());
        map.forEach((k, c) -> deltas.add(new RejectedDelta(k.tenantId(), k.creditorId(), k.tokenId(), k.hourStart(),
                k.reason(), k.method(), c.sum())));
        return deltas;
    }

    private static List<UsageDelta> toDeltas(Map<Key, Counters> map) {
        List<UsageDelta> deltas = new ArrayList<>(map.size());
        map.forEach((k, c) -> deltas.add(new UsageDelta(k.tenantId(), k.creditorId(), k.tokenId(), k.hourStart(),
                k.method(), k.route(), c.requests.sum(), c.throttled.sum(), c.clientErrors.sum(), c.serverErrors.sum(),
                c.bytesIn.sum(), c.bytesOut.sum(), c.billableBytesIn.sum(), c.billableBytesOut.sum(), c.records.sum(),
                c.latencyMs.sum())));
        return deltas;
    }

    private static Map<UUID, List<LastUse>> toLastUses(Map<UUID, LastUseHolder> uses) {
        Map<UUID, List<LastUse>> byTenant = new java.util.HashMap<>();
        uses.forEach((tokenId, h) -> byTenant.computeIfAbsent(h.tenantId(), t -> new ArrayList<>())
                .add(new LastUse(tokenId, h.at(), h.ip())));
        return byTenant;
    }
}
