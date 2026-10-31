package br.com.gestorcobranca.ingestion.domain.service.pdf;

import br.com.gestorcobranca.ingestion.domain.model.pdf.RecordDecisions;
import br.com.gestorcobranca.ingestion.domain.model.pdf.RecordDecisions.Decision;
import br.com.gestorcobranca.ingestion.domain.service.pdf.RecordDecisionPolicy.Target;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecordDecisionPolicyTest {

    private final RecordDecisionPolicy policy = new RecordDecisionPolicy();
    private final List<Target> mixed = List.of(new Target(1, true, false), new Target(2, true, true),
            new Target(3, false, false), new Target(4, true, true));

    @Test
    void bulkConfirmLeavesLowConfidenceAndTextRecordsOut() {
        var out = policy.decide(RecordDecisions.none(), Decision.CONFIRM, mixed, false);
        assertThat(out.applied()).containsExactly(1);
        assertThat(out.decisions().confirmed()).containsExactly(1);
        assertThat(out.skipped()).extracting(RecordDecisionPolicy.Skipped::code)
                .containsExactly(RecordDecisionPolicy.LOW_CONFIDENCE_ONE_BY_ONE, RecordDecisionPolicy.NOT_VISUAL);
        assertThat(out.skipped().getFirst().ordinals()).containsExactly(2, 4);
        assertThat(out.skippedCount()).isEqualTo(3);
    }

    @Test
    void lowConfidenceIsConfirmedOneByOne() {
        var out = policy.decide(RecordDecisions.none(), Decision.CONFIRM, List.of(new Target(2, true, true)), true);
        assertThat(out.applied()).containsExactly(2);
        assertThat(out.skipped()).isEmpty();
    }

    @Test
    void bulkExcludeIncludesLowConfidenceAndReportsAlreadyExcluded() {
        RecordDecisions current = new RecordDecisions(Set.of(3), Set.of(1));
        var out = policy.decide(current, Decision.EXCLUDE, mixed, false);
        assertThat(out.applied()).containsExactly(1, 2, 4);
        assertThat(out.decisions().excluded()).containsExactlyInAnyOrder(1, 2, 3, 4);
        assertThat(out.decisions().confirmed()).isEmpty();
        assertThat(out.skipped()).singleElement().satisfies(g -> {
            assertThat(g.code()).isEqualTo(RecordDecisionPolicy.ALREADY_EXCLUDED);
            assertThat(g.ordinals()).containsExactly(3);
        });

        var back = policy.decide(out.decisions(), Decision.INCLUDE, List.of(new Target(2, true, true),
                new Target(9, false, false)), false);
        assertThat(back.applied()).containsExactly(2);
        assertThat(back.skipped()).singleElement().extracting(RecordDecisionPolicy.Skipped::code)
                .isEqualTo(RecordDecisionPolicy.NOT_EXCLUDED);
    }

    @Test
    void nothingAllowedKeepsTheDecisions() {
        RecordDecisions current = new RecordDecisions(Set.of(), Set.of(1));
        var out = policy.decide(current, Decision.CONFIRM, List.of(new Target(1, true, false)), false);
        assertThat(out.applied()).isEmpty();
        assertThat(out.decisions()).isSameAs(current);
        assertThat(out.skipped()).singleElement().extracting(RecordDecisionPolicy.Skipped::code)
                .isEqualTo(RecordDecisionPolicy.ALREADY_CONFIRMED);
    }
}
