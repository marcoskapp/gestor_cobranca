package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases.ChecklistUpdate;
import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases.ScreenUpdate;
import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases.TourUpdate;
import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases.UiStatePatch;
import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases.UiStateView;
import br.com.gestorcobranca.identity.domain.model.UserUiState;
import br.com.gestorcobranca.identity.domain.repository.UserUiStateRepository;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    private final InMemoryRepository repository = new InMemoryRepository();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UiStateService service = new UiStateService(repository, new CurrentActor(), meters,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        act(new Actor(Actor.ActorType.USER, userId, UUID.randomUUID(), UUID.randomUUID(), "Operador", Set.of(),
                CreditorScope.NONE));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void act(Actor actor) {
        SecurityContextHolder.getContext().setAuthentication(ActorAuthentication.of(actor));
    }

    private static UiStatePatch patch(String theme, String density, List<TourUpdate> tours) {
        return new UiStatePatch(theme, density, null, null, null, null, tours, null, null);
    }

    @Test
    void returnsDefaultsWithoutPersistingAnything() {
        UiStateView view = service.myState();
        assertThat(view.theme()).isEqualTo("SYSTEM");
        assertThat(view.density()).isEqualTo("COMFORTABLE");
        assertThat(view.tours()).isEmpty();
        assertThat(view.hintsEnabled()).isTrue();
        assertThat(repository.states).isEmpty();
    }

    @Test
    void mergesPartialUpdatesKeepingUntouchedFields() {
        service.updateMyState(patch("DARK", null, null));
        UiStateView view = service.updateMyState(new UiStatePatch(null, "COMPACT", true, false, true, null, null,
                List.of(new ScreenUpdate("admin.users", 1)), List.of(new ChecklistUpdate("admin.invite-users", true))));

        assertThat(view.theme()).isEqualTo("DARK");
        assertThat(view.density()).isEqualTo("COMPACT");
        assertThat(view.navigationCollapsed()).isTrue();
        assertThat(view.hintsEnabled()).isFalse();
        assertThat(view.checklistDismissed()).isTrue();
        assertThat(view.screens()).containsKey("admin.users");
        assertThat(view.checklist()).containsEntry("admin.invite-users", NOW);
    }

    @Test
    void countsOnlyNewTourOutcomes() {
        TourUpdate done = new TourUpdate("shell.intro", 1, "COMPLETED");
        service.updateMyState(patch(null, null, List.of(done)));
        service.updateMyState(patch(null, null, List.of(done)));

        assertThat(meters.counter("ui.onboarding.tours", "outcome", "completed").count()).isEqualTo(1.0);
        assertThat(service.myState().tours().get("shell.intro").outcome()).isEqualTo("COMPLETED");
    }

    @Test
    void resetIsAppliedBeforeNewTourRecords() {
        service.updateMyState(patch(null, null, List.of(new TourUpdate("shell.intro", 1, "COMPLETED"),
                new TourUpdate("admin.users", 1, "DISMISSED"))));
        UiStateView view = service.updateMyState(new UiStatePatch(null, null, null, null, null, true,
                List.of(new TourUpdate("admin.users", 1, "COMPLETED")), null, null));

        assertThat(view.tours()).containsOnlyKeys("admin.users");
        assertThat(view.tours().get("admin.users").outcome()).isEqualTo("COMPLETED");
    }

    @Test
    void rejectsUnknownEnumValues() {
        assertThatThrownBy(() -> service.updateMyState(patch("NEON", null, null)))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.updateMyState(patch(null, null, List.of(new TourUpdate("a", 1, "SKIPPED")))))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void isAvailableOnlyToHumanUsers() {
        act(Actor.system(UUID.randomUUID(), "job"));
        assertThatThrownBy(service::myState).isInstanceOf(BusinessRuleException.class);
    }

    /** Dublê do repositório: um estado por usuário, criado sob demanda. */
    private static final class InMemoryRepository implements UserUiStateRepository {

        final Map<UUID, UserUiState> states = new HashMap<>();

        @Override
        public Optional<UserUiState> findByUserId(UUID userId) {
            return Optional.ofNullable(states.get(userId));
        }

        @Override
        public UserUiState lockOrCreate(UUID userId, Instant now) {
            return states.computeIfAbsent(userId, UserUiState::new);
        }
    }
}
