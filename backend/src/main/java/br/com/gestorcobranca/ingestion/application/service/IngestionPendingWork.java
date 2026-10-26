package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.port.out.IngestionPendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkItem;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRequest;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkSource;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pendências da conferência da importação: aprovação de segunda pessoa (mesma segregação de
 * {@code ImportReviewService#canApprove}: nem quem criou nem quem pediu a efetivação) e alertas de
 * valor a decidir nas conferências do próprio usuário.
 */
@Component
class IngestionPendingWork implements PendingWorkSource {

    static final String REVIEW_APPROVAL = "ingestion.review_approval";
    static final String REVIEW_ALERTS = "ingestion.review_alerts";

    private final IngestionPendingWorkQueries queries;
    private final AccessScope accessScope;

    IngestionPendingWork(IngestionPendingWorkQueries queries, AccessScope accessScope) {
        this.queries = queries;
        this.accessScope = accessScope;
    }

    @Override
    public String id() {
        return "ingestion";
    }

    @Override
    public String label() {
        return "Importação";
    }

    @Override
    public boolean appliesTo(Actor actor) {
        return actor.has(Permission.IMPORT_APPROVE) || actor.has(Permission.IMPORT_EXECUTE);
    }

    @Override
    public List<PendingWorkItem> pendingFor(PendingWorkRequest request) {
        Actor actor = request.actor();
        Set<UUID> scope = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        List<PendingWorkItem> items = new ArrayList<>();
        if (actor.has(Permission.IMPORT_APPROVE)) {
            items.add(PendingWorkItem.of(REVIEW_APPROVAL, "Importações aguardando a sua aprovação",
                            queries.awaitingApproval(scope, actor.id()))
                    .hint("Confira o impacto da carga e aprove ou recuse.")
                    .route("/importacao").singleRoute(id -> "/importacao/conferencias/" + id).build());
        }
        if (actor.has(Permission.IMPORT_EXECUTE)) {
            items.add(PendingWorkItem.of(REVIEW_ALERTS, "Conferências com alertas de valor a decidir",
                            queries.openAlerts(scope, actor.id()))
                    .hint("Confirme ou exclua as linhas com valor fora do esperado.")
                    .route("/importacao").singleRoute(id -> "/importacao/conferencias/" + id).build());
        }
        return items;
    }
}
