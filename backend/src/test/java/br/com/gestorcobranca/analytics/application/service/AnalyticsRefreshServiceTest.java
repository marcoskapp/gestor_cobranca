package br.com.gestorcobranca.analytics.application.service;

import br.com.gestorcobranca.analytics.application.AnalyticsProperties;
import br.com.gestorcobranca.analytics.application.port.out.AnalyticsReader;
import br.com.gestorcobranca.analytics.application.port.out.SnapshotStore;
import br.com.gestorcobranca.shared.domain.ConflictException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsRefreshServiceTest {

    final Instant now = Instant.parse("2026-10-24T13:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final SnapshotStore store = mock(SnapshotStore.class);
    final AnalyticsReader reader = mock(AnalyticsReader.class);
    final AnalyticsAccess access = mock(AnalyticsAccess.class);
    final AnalyticsProperties properties = new AnalyticsProperties(null, Duration.ofMinutes(10), Duration.ofMinutes(2),
            null, null);
    final AnalyticsRefreshService service = new AnalyticsRefreshService(store, reader, access,
            new AnalyticsMetrics(new SimpleMeterRegistry(), properties), properties, clock);

    @Test
    void manualRefreshLocksThenRebuildsEveryDatasetAndStamps() {
        when(store.lastRefreshedAt()).thenReturn(Optional.of(now.minusSeconds(600)));
        when(store.refresh(any(), any(), any())).thenReturn(7L);
        when(reader.freshness()).thenReturn(List.of());
        service.refreshNow();
        InOrder order = inOrder(store);
        order.verify(store).lockForRefresh(Duration.ofMinutes(10));
        order.verify(store).lastRefreshedAt();
        for (String dataset : AnalyticsRefreshService.DATASETS) {
            order.verify(store).refresh(dataset, LocalDate.of(2026, 10, 24), "America/Sao_Paulo");
            order.verify(store).saveState(eq(dataset), eq(now), eq(now), anyLong(), eq(7L));
        }
        assertThat(BigDecimal.ONE).isNotNull();
    }

    @Test
    void manualRefreshRespectsMinimumInterval() {
        when(store.lastRefreshedAt()).thenReturn(Optional.of(now.minusSeconds(30)));
        assertThatThrownBy(service::refreshNow).isInstanceOf(ConflictException.class)
                .hasMessageContaining("atualizados há pouco");
        verify(store, never()).refresh(any(), any(), any());
    }

    @Test
    void scheduledRefreshIgnoresMinimumInterval() {
        when(store.refresh(any(), any(), any())).thenReturn(2L);
        assertThat(service.refreshCurrentTenant()).isEqualTo(8L);
        verify(store, never()).lastRefreshedAt();
    }
}
