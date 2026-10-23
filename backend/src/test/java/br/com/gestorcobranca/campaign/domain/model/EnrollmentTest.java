package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static br.com.gestorcobranca.campaign.domain.CampaignFixtures.message;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ciclo de vida da inscrição. */
class EnrollmentTest {

    private static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");

    private static Enrollment.Origin origin(String group) {
        return new Enrollment.Origin(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), group, 100, null, null);
    }

    @Test
    void startSchedulesTheFirstStepAfterItsWait() {
        Enrollment e = Enrollment.start(origin("COBRANCA"), message("D0", 30, null, null), T0);
        assertThat(e.getStatus()).isEqualTo(EnrollmentStatus.ACTIVE);
        assertThat(e.getNextStepCode()).isEqualTo("D0");
        assertThat(e.getNextStepIteration()).isEqualTo(1);
        assertThat(e.getNextRunAt()).isEqualTo(T0.plus(Duration.ofMinutes(30)));
        assertThat(e.getStepDueAt()).isEqualTo(e.getNextRunAt());
        assertThat(e.observes(T0.plusSeconds(1))).isTrue();
        assertThat(e.observes(T0.minusSeconds(1))).isFalse();
    }

    @Test
    void pauseKeepsTheFirstReasonAndResumeRunsFromNow() {
        Enrollment e = Enrollment.start(origin(null), message("D0", 0, null, null), T0);
        assertThat(e.pause(PauseReason.DISPUTE_OPENED, T0, null)).isTrue();
        assertThat(e.pause(PauseReason.PAYMENT_CONFIRMED, T0, T0)).isFalse();
        assertThat(e.getPauseReason()).isEqualTo(PauseReason.DISPUTE_OPENED);
        Instant later = T0.plus(Duration.ofDays(2));
        e.resume(later);
        assertThat(e.getStatus()).isEqualTo(EnrollmentStatus.ACTIVE);
        assertThat(e.getNextRunAt()).isEqualTo(later);
        assertThat(e.getPauseReason()).isNull();
    }

    @Test
    void failureWaitsForReprocessingOnTheSameStepAndIteration() {
        Enrollment e = Enrollment.start(origin(null), message("D0", 0, null, null), T0);
        e.fail("message.template_not_found", T0);
        assertThat(e.getStatus()).isEqualTo(EnrollmentStatus.FAILED);
        assertThatThrownBy(() -> e.recordStep("D0", StepOutcome.EXECUTED, null, T0)).isInstanceOf(ConflictException.class);
        e.reprocess(T0.plusSeconds(60));
        assertThat(e.getStatus()).isEqualTo(EnrollmentStatus.ACTIVE);
        assertThat(e.getNextStepCode()).isEqualTo("D0");
        assertThat(e.getNextStepIteration()).isEqualTo(1);
        assertThat(e.getLastErrorCode()).isEqualTo("message.template_not_found");
    }

    @Test
    void iterationIsTheSequenceOfStepRuns() {
        Enrollment e = Enrollment.start(origin(null), message("D0", 0, null, null), T0);
        e.recordStep("D0", StepOutcome.EXECUTED, UUID.randomUUID(), T0);
        assertThat(e.nextIteration()).isEqualTo(2);
        e.recordStep("D1", StepOutcome.SKIPPED, null, T0);
        assertThat(e.getStepsExecuted()).isEqualTo(1);
        assertThat(e.getStepsSkipped()).isEqualTo(1);
        assertThat(e.nextIteration()).isEqualTo(3);
    }

    @Test
    void endingOpensTheAttributionWindowAndHoldoutObservesUntilTheHorizon() {
        Enrollment e = Enrollment.start(origin("G"), message("D0", 0, null, null), T0);
        e.end(EndReason.REPLIED, T0.plus(Duration.ofDays(1)), 7);
        assertThat(e.getStatus()).isEqualTo(EnrollmentStatus.ENDED);
        assertThat(e.observes(T0.plus(Duration.ofDays(8)))).isTrue();
        assertThat(e.observes(T0.plus(Duration.ofDays(9)))).isFalse();

        Enrollment holdout = Enrollment.holdout(origin("G"), T0, 30);
        assertThat(holdout.isControl()).isTrue();
        assertThat(holdout.getExclusivityGroup()).isNull();
        assertThat(holdout.observes(T0.plus(Duration.ofDays(30)))).isTrue();
        assertThat(holdout.observes(T0.plus(Duration.ofDays(31)))).isFalse();
    }

    @Test
    void firstReplyIsRegisteredOnce() {
        Enrollment e = Enrollment.start(origin(null), message("D0", 0, null, null), T0);
        assertThat(e.registerReply(T0)).isTrue();
        assertThat(e.registerReply(T0.plusSeconds(5))).isFalse();
        assertThat(e.getRepliedAt()).isEqualTo(T0);
    }
}
