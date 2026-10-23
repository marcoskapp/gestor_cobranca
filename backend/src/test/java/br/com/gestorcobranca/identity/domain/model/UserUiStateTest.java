package br.com.gestorcobranca.identity.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserUiStateTest {

    private static final Instant T1 = Instant.parse("2026-10-22T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-22T13:00:00Z");

    private final UserUiState state = new UserUiState(UUID.randomUUID());

    @Test
    void startsWithSystemThemeAndComfortableDensity() {
        assertThat(state.getTheme()).isEqualTo(UiTheme.SYSTEM);
        assertThat(state.getDensity()).isEqualTo(UiDensity.COMFORTABLE);
        assertThat(state.isNavigationCollapsed()).isFalse();
        assertThat(state.getTours()).isEmpty();
    }

    @Test
    void recordsTourAndIgnoresOlderVersions() {
        assertThat(state.recordTour("shell.intro", 2, TourOutcome.COMPLETED, T1)).isTrue();
        assertThat(state.recordTour("shell.intro", 1, TourOutcome.DISMISSED, T2)).isFalse();
        assertThat(state.recordTour("shell.intro", 2, TourOutcome.COMPLETED, T2)).isFalse();

        TourProgress progress = state.getTours().get("shell.intro");
        assertThat(progress.getVersion()).isEqualTo(2);
        assertThat(progress.getOutcome()).isEqualTo(TourOutcome.COMPLETED);
        assertThat(progress.getRecordedAt()).isEqualTo(T1);
    }

    @Test
    void newerTourVersionReplacesPreviousRecord() {
        state.recordTour("shell.intro", 1, TourOutcome.DISMISSED, T1);
        assertThat(state.recordTour("shell.intro", 2, TourOutcome.COMPLETED, T2)).isTrue();
        assertThat(state.getTours().get("shell.intro").getVersion()).isEqualTo(2);
    }

    @Test
    void resetForgetsAllTours() {
        state.recordTour("shell.intro", 1, TourOutcome.COMPLETED, T1);
        state.resetTours();
        assertThat(state.getTours()).isEmpty();
    }

    @Test
    void screenVersionNeverRegresses() {
        assertThat(state.markScreenSeen("admin.users", 3, T1)).isTrue();
        assertThat(state.markScreenSeen("admin.users", 2, T2)).isFalse();
        assertThat(state.markScreenSeen("admin.users", 3, T2)).isFalse();
        assertThat(state.getScreens().get("admin.users").getVersion()).isEqualTo(3);
        assertThat(state.markScreenSeen("admin.users", 4, T2)).isTrue();
        assertThat(state.getScreens().get("admin.users").getSeenAt()).isEqualTo(T2);
    }

    @Test
    void checklistKeepsFirstCompletionAndCanBeUnchecked() {
        state.setChecklistItem("admin.create-creditor", true, T1);
        state.setChecklistItem("admin.create-creditor", true, T2);
        assertThat(state.getChecklist()).containsEntry("admin.create-creditor", T1);
        state.setChecklistItem("admin.create-creditor", false, T2);
        assertThat(state.getChecklist()).isEmpty();
    }

    @Test
    void rejectsMalformedKeysAndVersions() {
        assertThatThrownBy(() -> state.recordTour("Shell Intro", 1, TourOutcome.COMPLETED, T1))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("tour");
        assertThatThrownBy(() -> state.recordTour("a".repeat(65), 1, TourOutcome.COMPLETED, T1))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> state.markScreenSeen("admin..users", 1, T1)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> state.markScreenSeen("admin.users", 0, T1)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> state.markScreenSeen("admin.users", 10_000, T1)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> state.setChecklistItem("<script>", true, T1)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void limitsTheNumberOfRecordedScreens() {
        for (int i = 0; i < UserUiState.MAX_SCREENS; i++) {
            state.markScreenSeen("screen-" + i, 1, T1);
        }
        assertThatThrownBy(() -> state.markScreenSeen("one-more", 1, T1)).isInstanceOf(BusinessRuleException.class);
        // telas já registradas continuam atualizáveis
        assertThat(state.markScreenSeen("screen-0", 2, T2)).isTrue();
    }
}
