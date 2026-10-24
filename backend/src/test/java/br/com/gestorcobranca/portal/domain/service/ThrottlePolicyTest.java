package br.com.gestorcobranca.portal.domain.service;

import br.com.gestorcobranca.portal.domain.service.ThrottlePolicy.Limits;
import br.com.gestorcobranca.portal.domain.service.ThrottlePolicy.State;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ThrottlePolicyTest {

    static final Limits LIMITS = new Limits(5, 3, Duration.ofMinutes(30), Duration.ofMinutes(15), Duration.ofHours(24));
    static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");

    @Test
    void fiveFailuresLockAndEachRelapseDoublesUpToTheMaximum() {
        State s = State.fresh(T0);
        for (int i = 0; i < 4; i++) {
            s = ThrottlePolicy.onFailure(s, LIMITS, T0);
            assertThat(s.lockedAt(T0)).isFalse();
        }
        s = ThrottlePolicy.onFailure(s, LIMITS, T0);
        assertThat(s.lockedUntil()).isEqualTo(T0.plus(Duration.ofMinutes(15)));
        assertThat(s.lockCount()).isEqualTo(1);
        // reincidência após o bloqueio: 30 min, depois 60 min…
        Instant t1 = s.lockedUntil().plusSeconds(1);
        for (int i = 0; i < 5; i++) {
            s = ThrottlePolicy.onFailure(s, LIMITS, t1);
        }
        assertThat(s.lockedUntil()).isEqualTo(t1.plus(Duration.ofMinutes(30)));
        assertThat(ThrottlePolicy.lockDuration(LIMITS, 20)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void requestsAboveLimitLockAndWindowExpiryResetsCounts() {
        State s = State.fresh(T0);
        for (int i = 0; i < 3; i++) {
            s = ThrottlePolicy.onRequest(s, LIMITS, T0);
        }
        assertThat(s.lockedAt(T0)).isFalse();
        State afterWindow = ThrottlePolicy.onRequest(s, LIMITS, T0.plus(Duration.ofMinutes(31)));
        assertThat(afterWindow.requests()).isEqualTo(1);
        assertThat(ThrottlePolicy.onRequest(s, LIMITS, T0).lockedAt(T0)).isTrue();
    }

    @Test
    void successResetsCountsAndOptionallyTheLockHistory() {
        State s = new State(T0, 3, 2, 2, null);
        assertThat(ThrottlePolicy.onSuccess(s, T0, false)).isEqualTo(new State(T0, 0, 0, 2, null));
        assertThat(ThrottlePolicy.onSuccess(s, T0, true).lockCount()).isZero();
    }
}
