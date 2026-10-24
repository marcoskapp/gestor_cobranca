package br.com.gestorcobranca.telephony.domain.service;

import br.com.gestorcobranca.telephony.domain.model.CallResult;
import br.com.gestorcobranca.telephony.domain.model.DialerEntryStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    static final Duration INTERVAL = Duration.ofMinutes(120);

    @Test
    void answeredCompletesTheEntry() {
        assertThat(RetryPolicy.after(CallResult.ANSWERED, 1, 3, INTERVAL, NOW).status())
                .isEqualTo(DialerEntryStatus.COMPLETED);
    }

    @Test
    void unansweredResultsRetryAfterTheIntervalUntilMaxAttempts() {
        for (CallResult result : new CallResult[]{CallResult.NO_ANSWER, CallResult.VOICEMAIL, CallResult.ABANDONED,
                CallResult.FAILED, CallResult.CANCELLED}) {
            RetryPolicy.Decision d = RetryPolicy.after(result, 1, 3, INTERVAL, NOW);
            assertThat(d.status()).isEqualTo(DialerEntryStatus.PENDING);
            assertThat(d.nextAttemptAt()).isEqualTo(NOW.plus(INTERVAL));
        }
        assertThat(RetryPolicy.after(CallResult.NO_ANSWER, 3, 3, INTERVAL, NOW).status())
                .isEqualTo(DialerEntryStatus.EXHAUSTED);
    }

    @Test
    void busyRetriesSoonerWithFloor() {
        assertThat(RetryPolicy.after(CallResult.BUSY, 1, 3, INTERVAL, NOW).nextAttemptAt())
                .isEqualTo(NOW.plus(Duration.ofMinutes(60)));
        assertThat(RetryPolicy.after(CallResult.BUSY, 1, 3, Duration.ofMinutes(6), NOW).nextAttemptAt())
                .isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void automaticTabulationByResult() {
        assertThat(CallTabulations.automatic(CallResult.NO_ANSWER)).isEqualTo("NO_ANSWER");
        assertThat(CallTabulations.automatic(CallResult.CANCELLED)).isEqualTo("NO_ANSWER");
        assertThat(CallTabulations.automatic(CallResult.BUSY)).isEqualTo("BUSY");
        assertThat(CallTabulations.automatic(CallResult.VOICEMAIL)).isEqualTo("VOICEMAIL");
        assertThat(CallTabulations.automatic(CallResult.ABANDONED)).isEqualTo("CALL_ABANDONED");
        assertThat(CallTabulations.automatic(CallResult.FAILED)).isEqualTo("CALL_FAILED");
        assertThat(CallTabulations.automatic(CallResult.ANSWERED)).isEqualTo("CALL_ANSWERED");
    }
}
