package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases;
import br.com.gestorcobranca.identity.domain.model.ScreenVisit;
import br.com.gestorcobranca.identity.domain.model.TourOutcome;
import br.com.gestorcobranca.identity.domain.model.TourProgress;
import br.com.gestorcobranca.identity.domain.model.UiDensity;
import br.com.gestorcobranca.identity.domain.model.UiTheme;
import br.com.gestorcobranca.identity.domain.model.UserUiState;
import br.com.gestorcobranca.identity.domain.repository.UserUiStateRepository;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Preferências visuais e progresso do onboarding do próprio usuário.
 * <p>
 * Não é dado de negócio nem de auditoria: o desfecho de tours vai para o log técnico e para a
 * métrica {@code ui.onboarding.tours{outcome}}, que alimentam a avaliação de adoção da interface.
 * O id do tour aparece só no log (cardinalidade controlada pelo cliente não entra em métricas).
 */
@Service
@Transactional
class UiStateService implements UiStateUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(UiStateService.class);

    private final UserUiStateRepository states;
    private final CurrentActor currentActor;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    UiStateService(UserUiStateRepository states, CurrentActor currentActor, MeterRegistry meterRegistry, Clock clock) {
        this.states = states;
        this.currentActor = currentActor;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public UiStateView myState() {
        return states.findByUserId(currentUserId()).map(UiStateService::toView).orElseGet(UiStateService::defaults);
    }

    @Override
    public UiStateView updateMyState(UiStatePatch patch) {
        Guard.requireNonNull(patch, "ui_state.patch_required", "Alterações não informadas");
        UUID userId = currentUserId();
        Instant now = clock.instant();
        UserUiState state = states.lockOrCreate(userId, now);

        if (patch.theme() != null) {
            state.chooseTheme(parse(UiTheme.class, patch.theme(), "ui_state.theme_invalid", "Tema inválido"));
        }
        if (patch.density() != null) {
            state.chooseDensity(parse(UiDensity.class, patch.density(), "ui_state.density_invalid", "Densidade inválida"));
        }
        if (patch.navigationCollapsed() != null) {
            state.collapseNavigation(patch.navigationCollapsed());
        }
        if (patch.hintsEnabled() != null) {
            state.enableHints(patch.hintsEnabled());
        }
        if (patch.checklistDismissed() != null) {
            state.dismissChecklist(patch.checklistDismissed());
        }
        if (Boolean.TRUE.equals(patch.resetTours())) {
            state.resetTours();
            LOG.atInfo().addKeyValue("event", "ui.onboarding.tours_reset").log("Tours reiniciados pelo usuário");
        }
        if (patch.tours() != null) {
            for (TourUpdate tour : patch.tours()) {
                Guard.requireNonNull(tour, "ui_state.tour_required", "Tour não informado");
                TourOutcome outcome = parse(TourOutcome.class, tour.outcome(), "ui_state.tour_outcome_invalid",
                        "Desfecho de tour inválido");
                if (state.recordTour(tour.tourId(), tour.version(), outcome, now)) {
                    meterRegistry.counter("ui.onboarding.tours", "outcome", outcome.name().toLowerCase()).increment();
                    LOG.atInfo()
                            .addKeyValue("event", "ui.onboarding.tour_recorded")
                            .addKeyValue("tourId", tour.tourId())
                            .addKeyValue("tourVersion", tour.version())
                            .addKeyValue("outcome", outcome.name())
                            .log("Desfecho de tour registrado");
                }
            }
        }
        if (patch.screens() != null) {
            for (ScreenUpdate screen : patch.screens()) {
                Guard.requireNonNull(screen, "ui_state.screen_required", "Tela não informada");
                state.markScreenSeen(screen.screenKey(), screen.version(), now);
            }
        }
        if (patch.checklist() != null) {
            for (ChecklistUpdate item : patch.checklist()) {
                Guard.requireNonNull(item, "ui_state.checklist_item_required", "Item não informado");
                state.setChecklistItem(item.itemId(), item.done(), now);
            }
        }
        return toView(state);
    }

    // ------------------------------------------------------------------ interno

    /** O estado de interface só existe para usuários humanos (nunca sistema, portal ou assistente). */
    private UUID currentUserId() {
        Actor actor = currentActor.get();
        Guard.require(actor.isUser(), "ui_state.user_only", "Disponível apenas para usuários");
        return actor.id();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String code, String message) {
        if (value == null) {
            throw new BusinessRuleException(code, message);
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(code, message);
        }
    }

    private static UiStateView defaults() {
        return new UiStateView(UiTheme.SYSTEM.name(), UiDensity.COMFORTABLE.name(), false, true, false, Map.of(), Map.of(), Map.of());
    }

    private static UiStateView toView(UserUiState state) {
        Map<String, TourView> tours = new TreeMap<>();
        for (Map.Entry<String, TourProgress> e : state.getTours().entrySet()) {
            TourProgress p = e.getValue();
            tours.put(e.getKey(), new TourView(p.getVersion(), p.getOutcome().name(), p.getRecordedAt()));
        }
        Map<String, ScreenView> screens = new TreeMap<>();
        for (Map.Entry<String, ScreenVisit> e : state.getScreens().entrySet()) {
            screens.put(e.getKey(), new ScreenView(e.getValue().getVersion(), e.getValue().getSeenAt()));
        }
        return new UiStateView(state.getTheme().name(), state.getDensity().name(), state.isNavigationCollapsed(),
                state.isHintsEnabled(), state.isChecklistDismissed(), tours, screens, new TreeMap<>(state.getChecklist()));
    }
}
