package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.incentive.application.port.out.IncentivePendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkItem;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRequest;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkSource;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Pendências da remuneração variável: aprovação financeira dos extratos (com a segregação do
 * extrato), contestações a resolver e ajustes aguardando segunda aprovação. Nenhum valor de
 * remuneração sai daqui — só contagens.
 */
@Component
class IncentivePendingWork implements PendingWorkSource {

    static final String STATEMENT_APPROVAL = "incentive.statement_approval";
    static final String DISPUTES = "incentive.statement_disputes";
    static final String ADJUSTMENT_APPROVAL = "incentive.adjustment_approval";

    private static final String LIST = "/remuneracao/extratos";

    private final IncentivePendingWorkQueries queries;

    IncentivePendingWork(IncentivePendingWorkQueries queries) {
        this.queries = queries;
    }

    @Override
    public String id() {
        return "incentive";
    }

    @Override
    public String label() {
        return "Remuneração variável";
    }

    @Override
    public boolean appliesTo(Actor actor) {
        return actor.has(Permission.COMMISSION_STATEMENT_APPROVE) || actor.has(Permission.COMMISSION_STATEMENT_MANAGE);
    }

    @Override
    public List<PendingWorkItem> pendingFor(PendingWorkRequest request) {
        Actor actor = request.actor();
        List<PendingWorkItem> items = new ArrayList<>();
        if (actor.has(Permission.COMMISSION_STATEMENT_APPROVE)) {
            items.add(PendingWorkItem.of(STATEMENT_APPROVAL, "Extratos de comissão para aprovar",
                            queries.statementsAwaitingApproval(actor.id()))
                    .hint("Aprove os extratos fechados para exportar à folha.")
                    .route(LIST).singleRoute(id -> LIST + "/" + id).build());
        }
        if (actor.has(Permission.COMMISSION_STATEMENT_MANAGE)) {
            items.add(PendingWorkItem.of(DISPUTES, "Contestações de extrato a resolver", queries.openDisputes(actor.id()))
                    .hint("Aceite com ajuste ou recuse com justificativa.")
                    .route(LIST).param("situacao", "DISPUTED").singleRoute(id -> LIST + "/" + id).build());
            items.add(PendingWorkItem.of(ADJUSTMENT_APPROVAL, "Ajustes de extrato aguardando segunda aprovação",
                            queries.adjustmentsAwaitingApproval(actor.id()))
                    .hint("Decida o ajuste pedido por outra pessoa.")
                    .route(LIST).singleRoute(id -> LIST + "/" + id).build());
        }
        return items;
    }
}
