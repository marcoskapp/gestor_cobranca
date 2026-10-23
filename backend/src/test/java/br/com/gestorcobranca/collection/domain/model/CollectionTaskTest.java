package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionTaskTest {

    static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    static final Duration TOLERANCE = Duration.ofMinutes(30);

    static CollectionTask callback(Instant due) {
        return new CollectionTask(CollectionCaseTest.newCase(), TaskType.CALLBACK, due, TOLERANCE, UUID.randomUUID(),
                "ligar", null, NOW);
    }

    @Test
    void mustBeInTheFuture() {
        assertThatThrownBy(() -> callback(NOW)).isInstanceOf(BusinessRuleException.class);
        assertThat(callback(NOW.plusSeconds(3600)).getToleranceUntil()).isEqualTo(NOW.plusSeconds(3600).plus(TOLERANCE));
    }

    @Test
    void completedWithinToleranceIsKept() {
        CollectionTask t = callback(NOW.plusSeconds(3600));
        assertThat(t.complete(UUID.randomUUID(), null, NOW.plusSeconds(3600).plus(TOLERANCE))).isTrue();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThatThrownBy(() -> t.complete(UUID.randomUUID(), null, NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void missedAfterToleranceAndLateCompletionIsNotKept() {
        CollectionTask t = callback(NOW.plusSeconds(3600));
        Instant afterTolerance = NOW.plusSeconds(3600).plus(TOLERANCE).plusSeconds(1);
        assertThat(t.markMissedIfLate(NOW.plusSeconds(3600))).isFalse();
        assertThat(t.markMissedIfLate(afterTolerance)).isTrue();
        assertThat(t.markMissedIfLate(afterTolerance)).isFalse();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.MISSED);
        assertThat(t.complete(UUID.randomUUID(), null, afterTolerance.plusSeconds(60))).isFalse();
        assertThat(t.wasMissed()).isTrue();
    }

    @Test
    void rescheduleIsANewCommitment() {
        CollectionTask t = callback(NOW.plusSeconds(60));
        t.markMissedIfLate(NOW.plus(Duration.ofHours(2)));
        t.reschedule(NOW.plus(Duration.ofHours(5)), TOLERANCE, NOW.plus(Duration.ofHours(2)));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(t.wasMissed()).isFalse();
        assertThatThrownBy(() -> t.cancel(" ")).isInstanceOf(BusinessRuleException.class);
        t.cancel("devedor pediu outro canal");
        assertThat(t.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }
}
