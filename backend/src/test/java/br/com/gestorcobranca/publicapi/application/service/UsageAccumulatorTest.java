package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.publicapi.application.port.out.UsageDelta;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class UsageAccumulatorTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID creditor = UUID.randomUUID();
    private final UUID token = UUID.randomUUID();

    @Test
    void aggregatesByHourAndRouteAndClassifiesStatus() {
        UsageAccumulator acc = new UsageAccumulator();
        Instant t = Instant.parse("2026-10-30T10:15:00Z");
        acc.add(tenant, creditor, token, t, "GET", "/public-api/v1/titles", 200, 10, 1000, 0, 5);
        acc.add(tenant, creditor, token, t.plusSeconds(60), "GET", "/public-api/v1/titles", 429, 0, 200, 0, 1);
        acc.add(tenant, creditor, token, t.plusSeconds(120), "GET", "/public-api/v1/titles", 404, 0, 150, 0, 2);
        acc.add(tenant, creditor, token, t.plusSeconds(3600), "GET", "/public-api/v1/titles", 503, 0, 100, 0, 3);
        List<UsageDelta> deltas = acc.drainAll().deltas();
        assertThat(deltas).hasSize(2);
        UsageDelta first = deltas.stream().filter(d -> d.hourStart().equals(Instant.parse("2026-10-30T10:00:00Z")))
                .findFirst().orElseThrow();
        assertThat(first.requests()).isEqualTo(3);
        assertThat(first.throttled()).isEqualTo(1);
        assertThat(first.clientErrors()).isEqualTo(1);
        assertThat(first.bytesOut()).isEqualTo(1350);
        // tráfego cobrável: só o das respostas 2xx/3xx (429 e 404 ficam fora)
        assertThat(first.billableBytesOut()).isEqualTo(1000);
        assertThat(first.billableBytesIn()).isEqualTo(10);
        UsageDelta second = deltas.stream().filter(d -> d != first).findFirst().orElseThrow();
        assertThat(second.serverErrors()).isEqualTo(1);
        assertThat(second.billableBytesOut()).isZero();
        assertThat(acc.drainAll().deltas()).isEmpty();
    }

    @Test
    void concurrentRequestsDuringDrainAreNeverLost() throws Exception {
        UsageAccumulator acc = new UsageAccumulator();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        long[] written = {0};
        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                for (int j = 0; j < 5_000; j++) {
                    acc.add(tenant, creditor, token, Instant.now(), "POST", "/public-api/v1/imports", 202, 100, 50, 1, 1);
                }
            });
        }
        for (int k = 0; k < 20; k++) {
            written[0] += acc.drain().deltas().stream().mapToLong(UsageDelta::requests).sum();
            Thread.sleep(2);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        written[0] += acc.drainAll().deltas().stream().mapToLong(UsageDelta::requests).sum();
        assertThat(written[0]).isEqualTo(40_000);
    }

    @Test
    void restoredDeltasAreWrittenAgain() {
        UsageAccumulator acc = new UsageAccumulator();
        acc.add(tenant, creditor, token, Instant.now(), "GET", "/r", 200, 1, 2, 3, 4);
        UsageDelta d = acc.drainAll().deltas().getFirst();
        acc.restore(d);
        assertThat(acc.drainAll().deltas()).singleElement().satisfies(x -> {
            assertThat(x.requests()).isEqualTo(1);
            assertThat(x.records()).isEqualTo(3);
            assertThat(x.billableBytesOut()).isEqualTo(2);
        });
    }
}
