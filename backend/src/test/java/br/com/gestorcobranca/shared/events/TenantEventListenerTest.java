package br.com.gestorcobranca.shared.events;

import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantEventListenerTest {

    record SampleEvent(UUID eventId, UUID tenantId, Instant occurredAt) implements IntegrationEvent {
    }

    static class SampleListener extends TenantEventListener {
        void consume(IntegrationEvent event, Runnable action) {
            handleOnce(event, "test.sample", action);
        }
    }

    private final TenantExecution tenantExecution = mock(TenantExecution.class);
    private final ConsumedEventRegistry registry = mock(ConsumedEventRegistry.class);
    private final SampleListener listener = new SampleListener();
    private final SampleEvent event = new SampleEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.now());

    @BeforeEach
    void setUp() {
        listener.setInfrastructure(tenantExecution, registry);
        when(registry.markConsumed(anyString(), any())).thenReturn(true);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(2)).run();
            return null;
        }).when(tenantExecution).inTenant(any(), anyString(), any());
    }

    @Test
    void repeatsTheWholeTransactionAfterATransientConflict() {
        AtomicInteger calls = new AtomicInteger();
        listener.consume(event, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new ObjectOptimisticLockingFailureException("Case", UUID.randomUUID());
            }
        });
        assertThat(calls).hasValue(3);
    }

    @Test
    void givesUpAfterTheMaximumAttemptsSoThePublicationStaysIncomplete() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> listener.consume(event, () -> {
            calls.incrementAndGet();
            throw new ObjectOptimisticLockingFailureException("Case", UUID.randomUUID());
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(calls).hasValue(TenantEventListener.MAX_ATTEMPTS);
    }

    @Test
    void doesNotRepeatNonTransientFailures() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> listener.consume(event, () -> {
            calls.incrementAndGet();
            throw new DataIntegrityViolationException("duplicada");
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void skipsAlreadyConsumedEvents() {
        when(registry.markConsumed(anyString(), any())).thenReturn(false);
        AtomicInteger calls = new AtomicInteger();
        listener.consume(event, calls::incrementAndGet);
        assertThat(calls).hasValue(0);
    }
}
