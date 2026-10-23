package br.com.gestorcobranca.identity.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Estado de interface do usuário autenticado (preferências visuais e progresso do onboarding).
 * Sempre opera sobre o próprio usuário da sessão: não existe acesso ao estado de terceiros.
 */
public interface UiStateUseCases {

    /** Estado atual; valores padrão quando o usuário ainda não registrou nada (sem gravar). */
    UiStateView myState();

    /** Aplica as alterações informadas (campos nulos são mantidos) e devolve o estado resultante. */
    UiStateView updateMyState(UiStatePatch patch);

    /**
     * Alteração parcial. Listas são de "upsert": cada item registra um tour, uma tela vista ou um
     * item de checklist, sem apagar os demais. {@code resetTours} é aplicado antes de {@code tours}.
     *
     * @param theme               {@code SYSTEM}, {@code LIGHT} ou {@code DARK}
     * @param density             {@code COMFORTABLE} ou {@code COMPACT}
     * @param navigationCollapsed menu lateral recolhido
     * @param hintsEnabled        oferecer tours e novidades automaticamente
     * @param checklistDismissed  checklist de primeiros passos oculto
     */
    record UiStatePatch(String theme, String density, Boolean navigationCollapsed, Boolean hintsEnabled,
                        Boolean checklistDismissed, Boolean resetTours,
                        List<TourUpdate> tours, List<ScreenUpdate> screens, List<ChecklistUpdate> checklist) {
    }

    /** @param outcome {@code COMPLETED} ou {@code DISMISSED} */
    record TourUpdate(String tourId, int version, String outcome) {
    }

    record ScreenUpdate(String screenKey, int version) {
    }

    record ChecklistUpdate(String itemId, boolean done) {
    }

    record UiStateView(String theme, String density, boolean navigationCollapsed, boolean hintsEnabled,
                       boolean checklistDismissed, Map<String, TourView> tours,
                       Map<String, ScreenView> screens, Map<String, Instant> checklist) {
    }

    record TourView(int version, String outcome, Instant recordedAt) {
    }

    record ScreenView(int version, Instant seenAt) {
    }
}
