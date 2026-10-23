package br.com.gestorcobranca.identity.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Estado de interface de um usuário: preferências visuais e progresso do onboarding (tours,
 * telas vistas por versão e checklist de primeiros passos). Pertence exclusivamente ao usuário
 * e não contém dado pessoal além do id.
 * <p>
 * Os identificadores (tour, tela, item) são definidos pelo frontend; o domínio limita formato,
 * versões e quantidade para que o estado continue pequeno e não sirva de depósito arbitrário.
 */
@Entity
@Table(name = "user_ui_state", schema = "identity")
public class UserUiState extends TenantScopedEntity {

    /** Formato dos identificadores: minúsculas, dígitos e separadores {@code . -} (ex.: {@code admin.users-list}). */
    public static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9]+(?:[.-][a-z0-9]+)*$");
    public static final int KEY_MAX_LENGTH = 64;
    public static final int MAX_VERSION = 9999;
    public static final int MAX_TOURS = 200;
    public static final int MAX_SCREENS = 500;
    public static final int MAX_CHECKLIST_ITEMS = 100;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "theme", nullable = false, length = 10)
    private UiTheme theme;

    @Enumerated(EnumType.STRING)
    @Column(name = "density", nullable = false, length = 12)
    private UiDensity density;

    @Column(name = "navigation_collapsed", nullable = false)
    private boolean navigationCollapsed;

    /** Oferecer tours e destaques de novidades automaticamente ao abrir telas. */
    @Column(name = "hints_enabled", nullable = false)
    private boolean hintsEnabled;

    /** Checklist de primeiros passos ocultado pelo usuário. */
    @Column(name = "checklist_dismissed", nullable = false)
    private boolean checklistDismissed;

    @ElementCollection
    @CollectionTable(name = "user_ui_tour", schema = "identity", joinColumns = @JoinColumn(name = "ui_state_id"))
    @MapKeyColumn(name = "tour_id", length = KEY_MAX_LENGTH)
    private Map<String, TourProgress> tours = new HashMap<>();

    @ElementCollection
    @CollectionTable(name = "user_ui_screen", schema = "identity", joinColumns = @JoinColumn(name = "ui_state_id"))
    @MapKeyColumn(name = "screen_key", length = KEY_MAX_LENGTH)
    private Map<String, ScreenVisit> screens = new HashMap<>();

    @ElementCollection
    @CollectionTable(name = "user_ui_checklist", schema = "identity", joinColumns = @JoinColumn(name = "ui_state_id"))
    @MapKeyColumn(name = "item_id", length = KEY_MAX_LENGTH)
    @Column(name = "completed_at", nullable = false)
    private Map<String, Instant> checklist = new HashMap<>();

    protected UserUiState() {
    }

    public UserUiState(UUID userId) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.theme = UiTheme.SYSTEM;
        this.density = UiDensity.COMFORTABLE;
        this.hintsEnabled = true;
    }

    // ------------------------------------------------------------------ preferências

    public void chooseTheme(UiTheme theme) {
        this.theme = Guard.requireNonNull(theme, "ui_state.theme_required", "Tema obrigatório");
    }

    public void chooseDensity(UiDensity density) {
        this.density = Guard.requireNonNull(density, "ui_state.density_required", "Densidade obrigatória");
    }

    public void collapseNavigation(boolean collapsed) {
        this.navigationCollapsed = collapsed;
    }

    public void enableHints(boolean enabled) {
        this.hintsEnabled = enabled;
    }

    public void dismissChecklist(boolean dismissed) {
        this.checklistDismissed = dismissed;
    }

    // ------------------------------------------------------------------ onboarding

    /**
     * Registra o desfecho de um tour. Uma versão mais antiga que a já registrada é ignorada (ex.:
     * aba desatualizada), para que um tour revisado não volte a ser considerado visto.
     *
     * @return {@code true} se o registro mudou
     */
    public boolean recordTour(String tourId, int version, TourOutcome outcome, Instant now) {
        requireKey(tourId, "ui_state.tour_id_invalid", "Identificador de tour inválido");
        requireVersion(version);
        Guard.requireNonNull(outcome, "ui_state.tour_outcome_required", "Desfecho do tour obrigatório");
        TourProgress current = tours.get(tourId);
        if (current != null && (current.getVersion() > version
                || current.getVersion() == version && current.getOutcome() == outcome)) {
            return false;
        }
        Guard.require(current != null || tours.size() < MAX_TOURS, "ui_state.too_many_tours",
                "Limite de tours registrados atingido");
        tours.put(tourId, new TourProgress(version, outcome, now));
        return true;
    }

    /** Esquece todos os tours para que voltem a ser oferecidos (ação "rever tours" da ajuda). */
    public void resetTours() {
        tours.clear();
    }

    /**
     * Marca a versão de uma tela como vista. Nunca regride: versões menores ou iguais à já vista
     * são ignoradas.
     *
     * @return {@code true} se o registro mudou
     */
    public boolean markScreenSeen(String screenKey, int version, Instant now) {
        requireKey(screenKey, "ui_state.screen_key_invalid", "Identificador de tela inválido");
        requireVersion(version);
        ScreenVisit current = screens.get(screenKey);
        if (current != null && current.getVersion() >= version) {
            return false;
        }
        Guard.require(current != null || screens.size() < MAX_SCREENS, "ui_state.too_many_screens",
                "Limite de telas registradas atingido");
        screens.put(screenKey, new ScreenVisit(version, now));
        return true;
    }

    /** Marca ou desmarca um item do checklist de primeiros passos. */
    public void setChecklistItem(String itemId, boolean done, Instant now) {
        requireKey(itemId, "ui_state.checklist_item_invalid", "Identificador de item inválido");
        if (!done) {
            checklist.remove(itemId);
            return;
        }
        if (checklist.containsKey(itemId)) {
            return;
        }
        Guard.require(checklist.size() < MAX_CHECKLIST_ITEMS, "ui_state.too_many_checklist_items",
                "Limite de itens do checklist atingido");
        checklist.put(itemId, now);
    }

    private static void requireKey(String key, String code, String message) {
        Guard.require(key != null && key.length() <= KEY_MAX_LENGTH && KEY_PATTERN.matcher(key).matches(), code, message);
    }

    private static void requireVersion(int version) {
        Guard.require(version >= 1 && version <= MAX_VERSION, "ui_state.version_invalid", "Versão inválida");
    }

    // ------------------------------------------------------------------ leitura

    public UUID getUserId() {
        return userId;
    }

    public UiTheme getTheme() {
        return theme;
    }

    public UiDensity getDensity() {
        return density;
    }

    public boolean isNavigationCollapsed() {
        return navigationCollapsed;
    }

    public boolean isHintsEnabled() {
        return hintsEnabled;
    }

    public boolean isChecklistDismissed() {
        return checklistDismissed;
    }

    public Map<String, TourProgress> getTours() {
        return Collections.unmodifiableMap(tours);
    }

    public Map<String, ScreenVisit> getScreens() {
        return Collections.unmodifiableMap(screens);
    }

    public Map<String, Instant> getChecklist() {
        return Collections.unmodifiableMap(checklist);
    }
}
