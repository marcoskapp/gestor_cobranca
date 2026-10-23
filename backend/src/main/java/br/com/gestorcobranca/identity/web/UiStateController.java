package br.com.gestorcobranca.identity.web;

import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases;
import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases.ChecklistUpdate;
import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases.ScreenUpdate;
import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases.TourUpdate;
import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases.UiStatePatch;
import br.com.gestorcobranca.identity.application.port.in.UiStateUseCases.UiStateView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Estado de interface do usuário autenticado: tema, densidade, menu recolhido e progresso do
 * onboarding. Não recebe id de usuário — sempre o da própria sessão.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/me/ui-state")
@PreAuthorize("hasAuthority('ACTOR_USER')")
class UiStateController {

    private static final String KEY = "^[a-z0-9]+(?:[.-][a-z0-9]+)*$";
    private static final int MAX_ITEMS_PER_REQUEST = 50;

    private final UiStateUseCases uiState;

    UiStateController(UiStateUseCases uiState) {
        this.uiState = uiState;
    }

    @GetMapping
    UiStateView get() {
        return uiState.myState();
    }

    @PatchMapping
    UiStateView update(@Valid @RequestBody UiStatePatchRequest request) {
        return uiState.updateMyState(request.toPatch());
    }

    record UiStatePatchRequest(
            @Pattern(regexp = "SYSTEM|LIGHT|DARK") String theme,
            @Pattern(regexp = "COMFORTABLE|COMPACT") String density,
            Boolean navigationCollapsed,
            Boolean hintsEnabled,
            Boolean checklistDismissed,
            Boolean resetTours,
            @Size(max = MAX_ITEMS_PER_REQUEST) List<@NotNull @Valid TourRequest> tours,
            @Size(max = MAX_ITEMS_PER_REQUEST) List<@NotNull @Valid ScreenRequest> screens,
            @Size(max = MAX_ITEMS_PER_REQUEST) List<@NotNull @Valid ChecklistRequest> checklist) {

        UiStatePatch toPatch() {
            return new UiStatePatch(theme, density, navigationCollapsed, hintsEnabled, checklistDismissed, resetTours,
                    tours == null ? null : tours.stream().map(t -> new TourUpdate(t.tourId(), t.version(), t.outcome())).toList(),
                    screens == null ? null : screens.stream().map(s -> new ScreenUpdate(s.screenKey(), s.version())).toList(),
                    checklist == null ? null : checklist.stream().map(c -> new ChecklistUpdate(c.itemId(), c.done())).toList());
        }
    }

    record TourRequest(@NotNull @Size(max = 64) @Pattern(regexp = KEY) String tourId,
                       @NotNull @Min(1) @Max(9999) Integer version,
                       @NotNull @Pattern(regexp = "COMPLETED|DISMISSED") String outcome) {
    }

    record ScreenRequest(@NotNull @Size(max = 64) @Pattern(regexp = KEY) String screenKey,
                         @NotNull @Min(1) @Max(9999) Integer version) {
    }

    record ChecklistRequest(@NotNull @Size(max = 64) @Pattern(regexp = KEY) String itemId,
                            @NotNull Boolean done) {
    }
}
