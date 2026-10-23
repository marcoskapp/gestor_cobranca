package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseAssignmentAndTabulationTest {

    static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");

    @Test
    void assignmentPeriodIsClosedOnceAndCarriesThePreviousOwner() {
        CollectionCase c = CollectionCaseTest.newCase();
        c.routeTo(UUID.randomUUID());
        CaseAssignment first = new CaseAssignment(c, AssignmentMode.OPENED, null, null, T0);
        assertThat(first.getOwnerType()).isEqualTo(OwnerType.QUEUE);
        assertThat(first.isCurrent()).isTrue();
        UUID user = UUID.randomUUID();
        c.assignToUser(user, null, T0.plusSeconds(60));
        first.close(T0.plusSeconds(60));
        first.close(T0.plusSeconds(120));
        assertThat(first.getValidTo()).isEqualTo(T0.plusSeconds(60));
        assertThat(first.duration(T0.plusSeconds(999))).isEqualTo(Duration.ofSeconds(60));
        CaseAssignment second = new CaseAssignment(c, AssignmentMode.NEXT_CASE, first, "x".repeat(600), T0.plusSeconds(60));
        assertThat(second.getPreviousOwnerType()).isEqualTo(OwnerType.QUEUE);
        assertThat(second.getUserId()).isEqualTo(user);
        assertThat(second.getReason()).hasSize(500);
        assertThat(second.sameOwnerAs(c)).isTrue();
    }

    @Test
    void tabulationFlagsAreConsistent() {
        assertThatThrownBy(() -> new Tabulation("PROMISE_X", new Tabulation.Definition("x", false, true, false, false, 1)))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("tabulation.promise_requires_contact");
        assertThatThrownBy(() -> new Tabulation("BOTH", new Tabulation.Definition("x", true, true, true, false, 1)))
                .extracting("code").isEqualTo("tabulation.promise_and_callback");
        assertThatThrownBy(() -> new Tabulation("a b", new Tabulation.Definition("x", false, false, false, false, 1)))
                .extracting("code").isEqualTo("tabulation.code_invalid");
        Tabulation t = new Tabulation("cpc_ok", new Tabulation.Definition("CPC", true, false, false, true, 1));
        assertThat(t.getCode()).isEqualTo("CPC_OK");
        assertThat(DefaultTabulations.catalog()).extracting(DefaultTabulations.Entry::code).doesNotHaveDuplicates()
                .contains("PROMISE", "CALLBACK", "NO_ANSWER");
        DefaultTabulations.catalog().forEach(e -> new Tabulation(e.code(), e.definition()));
    }

    @Test
    void settingsValuesAreBounded() {
        assertThatThrownBy(() -> new CollectionSettings.Values(31, 30, 30, 10)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new CollectionSettings.Values(2, 0, 30, 10)).isInstanceOf(BusinessRuleException.class);
        assertThat(new CollectionSettings.Values(2, 30, 45, 10).callbackTolerance()).isEqualTo(Duration.ofMinutes(45));
    }
}
