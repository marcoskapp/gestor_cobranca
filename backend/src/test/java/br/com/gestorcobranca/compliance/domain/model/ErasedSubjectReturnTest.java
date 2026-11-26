package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.compliance.domain.model.ErasedSubjectReturn.Outcome;
import br.com.gestorcobranca.compliance.domain.model.ErasedSubjectReturn.Source;
import br.com.gestorcobranca.shared.domain.Ids;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ErasedSubjectReturnTest {

    private static final Instant NOW = Instant.parse("2026-11-26T15:00:00Z");

    @Test
    void importAttemptNeedsTheBatchAndFormAttemptHasNone() {
        assertThatThrownBy(() -> new ErasedSubjectReturn(Ids.newId(), Ids.newId(), Source.IMPORT, null, 1, NOW))
                .hasFieldOrPropertyWithValue("code", "erased_subject_return.batch_invalid");
        assertThatThrownBy(() -> new ErasedSubjectReturn(Ids.newId(), null, Source.DEBTOR_FORM, Ids.newId(), 1, NOW))
                .hasFieldOrPropertyWithValue("code", "erased_subject_return.batch_invalid");
        assertThatThrownBy(() -> new ErasedSubjectReturn(Ids.newId(), null, Source.DEBTOR_FORM, null, 0, NOW))
                .hasFieldOrPropertyWithValue("code", "erased_subject_return.lines_invalid");
    }

    /** Decisão 1604 e SEG-20261126-E12: sem saber quem eliminou, não há segregação, e a liberação é recusada. */
    @Test
    void releaseNeedsAnotherPersonThanTheKnownExecutor() {
        DataSubjectRequest request = DataSubjectRequestTest.request(RequestType.ANONYMIZATION);
        UUID executor = Ids.newId();
        request.verifyIdentity(Ids.newId(), executor, NOW);
        request.start(executor);
        request.recordErasure(NOW, executor, "b".repeat(64));
        assertThatThrownBy(() -> new ErasedSubjectRelease(request, "Titular voltou a contratar", executor, NOW))
                .hasFieldOrPropertyWithValue("code", "erased_subject_release.same_person");
        assertThat(new ErasedSubjectRelease(request, "Titular voltou a contratar", Ids.newId(), NOW).getRequestId())
                .isEqualTo(request.getId());

        DataSubjectRequest migrated = DataSubjectRequestTest.request(RequestType.ANONYMIZATION);
        migrated.verifyIdentity(Ids.newId(), executor, NOW);
        migrated.start(executor);
        migrated.recordErasure(NOW, executor, "c".repeat(64));
        org.springframework.test.util.ReflectionTestUtils.setField(migrated, "executedBy", null);
        assertThatThrownBy(() -> new ErasedSubjectRelease(migrated, "Titular voltou a contratar", Ids.newId(), NOW))
                .hasFieldOrPropertyWithValue("code", "erased_subject_release.executor_unknown");
    }

    @Test
    void reviewHappensOnce() {
        ErasedSubjectReturn attempt = new ErasedSubjectReturn(Ids.newId(), Ids.newId(), Source.IMPORT, Ids.newId(), 3, NOW);
        UUID reviewer = Ids.newId();
        attempt.review(Outcome.KEPT, reviewer, NOW.plusSeconds(60));
        assertThat(attempt.getOutcome()).isEqualTo(Outcome.KEPT);
        assertThat(attempt.getReviewedBy()).isEqualTo(reviewer);
        assertThatThrownBy(() -> attempt.review(Outcome.RELEASED, reviewer, NOW))
                .hasFieldOrPropertyWithValue("code", "erased_subject_return.already_reviewed");
    }
}
