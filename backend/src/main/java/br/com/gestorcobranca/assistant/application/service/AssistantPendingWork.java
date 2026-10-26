package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.port.out.AssistantPendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkItem;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRequest;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkSource;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Revisão humana por amostragem das conversas do assistente (fila da tela de revisão, no escopo de credores). */
@Component
class AssistantPendingWork implements PendingWorkSource {

    static final String REVIEWS = "assistant.turn_reviews";

    private final AssistantPendingWorkQueries queries;
    private final AccessScope accessScope;

    AssistantPendingWork(AssistantPendingWorkQueries queries, AccessScope accessScope) {
        this.queries = queries;
        this.accessScope = accessScope;
    }

    @Override
    public String id() {
        return "assistant";
    }

    @Override
    public String label() {
        return "Assistente de IA";
    }

    @Override
    public boolean appliesTo(Actor actor) {
        return actor.has(Permission.ASSISTANT_REVIEW);
    }

    @Override
    public List<PendingWorkItem> pendingFor(PendingWorkRequest request) {
        Set<UUID> scope = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        return List.of(PendingWorkItem.of(REVIEWS, "Turnos do assistente aguardando revisão", queries.pendingReviews(scope))
                .hint("Avalie a amostra para acompanhar a qualidade do assistente.")
                .route("/assistente/revisao").build());
    }
}
