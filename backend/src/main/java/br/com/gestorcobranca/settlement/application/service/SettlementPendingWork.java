package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.out.SettlementPendingWorkQueries;
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
 * Pendências da prestação de contas: revisão dos demonstrativos calculados, aprovação dos revisados
 * (segregada de quem calculou e de quem pediu ajuste — {@code SettlementStatement#approve}) e segunda
 * aprovação dos ajustes pedidos por outra pessoa.
 */
@Component
class SettlementPendingWork implements PendingWorkSource {

    static final String STATEMENT_REVIEW = "settlement.statement_review";
    static final String STATEMENT_APPROVAL = "settlement.statement_approval";
    static final String ADJUSTMENT_APPROVAL = "settlement.adjustment_approval";

    private static final String LIST = "/prestacao-de-contas/demonstrativos";

    private final SettlementPendingWorkQueries queries;
    private final AccessScope accessScope;

    SettlementPendingWork(SettlementPendingWorkQueries queries, AccessScope accessScope) {
        this.queries = queries;
        this.accessScope = accessScope;
    }

    @Override
    public String id() {
        return "settlement";
    }

    @Override
    public String label() {
        return "Prestação de contas";
    }

    @Override
    public boolean appliesTo(Actor actor) {
        return actor.has(Permission.SETTLEMENT_CLOSE) || actor.has(Permission.SETTLEMENT_APPROVE);
    }

    @Override
    public List<PendingWorkItem> pendingFor(PendingWorkRequest request) {
        Actor actor = request.actor();
        Set<UUID> scope = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        List<PendingWorkItem> items = new ArrayList<>();
        if (actor.has(Permission.SETTLEMENT_CLOSE)) {
            items.add(PendingWorkItem.of(STATEMENT_REVIEW, "Demonstrativos calculados para revisar",
                            queries.awaitingReview(scope))
                    .hint("Confira os valores e marque como revisado.")
                    .route(LIST).param("situacao", "CALCULATED").singleRoute(id -> LIST + "/" + id).build());
        }
        if (actor.has(Permission.SETTLEMENT_APPROVE)) {
            items.add(PendingWorkItem.of(STATEMENT_APPROVAL, "Demonstrativos revisados para aprovar",
                            queries.awaitingApproval(scope, actor.id()))
                    .hint("Aprove para liberar o envio ao credor.")
                    .route(LIST).param("situacao", "REVIEWED").singleRoute(id -> LIST + "/" + id).build());
            items.add(PendingWorkItem.of(ADJUSTMENT_APPROVAL, "Ajustes de demonstrativo aguardando segunda aprovação",
                            queries.adjustmentsAwaitingApproval(scope, actor.id()))
                    .hint("Decida o ajuste pedido por outra pessoa.")
                    .route(LIST).singleRoute(id -> LIST + "/" + id).build());
        }
        return items;
    }
}
