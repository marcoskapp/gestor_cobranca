package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.out.DebtPendingWorkQueries;
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
 * Pendências do debt: correções de valor de título aguardando aprovação (quem pediu não vê o próprio pedido), aumento
 * do limite de correção aguardando a confirmação de outra pessoa e divergências entre a correção e a carga do credor. Mesmos critérios de {@code TitleAmountAdjustmentService}.
 */
@Component
class DebtPendingWork implements PendingWorkSource {

    static final String AMOUNT_APPROVALS = "debt.amount_adjustment_approvals";
    static final String AMOUNT_DIVERGENCES = "debt.amount_divergences";
    static final String THRESHOLD_PROPOSAL = "debt.amount_threshold_proposal";

    private final DebtPendingWorkQueries queries;
    private final AccessScope accessScope;

    DebtPendingWork(DebtPendingWorkQueries queries, AccessScope accessScope) {
        this.queries = queries;
        this.accessScope = accessScope;
    }

    @Override
    public String id() {
        return "debt";
    }

    @Override
    public String label() {
        return "Títulos";
    }

    @Override
    public boolean appliesTo(Actor actor) {
        return actor.has(Permission.TITLE_AMOUNT_APPROVE) || actor.has(Permission.TITLE_AMOUNT_ADJUST);
    }

    @Override
    public List<PendingWorkItem> pendingFor(PendingWorkRequest request) {
        Actor actor = request.actor();
        Set<UUID> scope = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        List<PendingWorkItem> items = new ArrayList<>();
        if (actor.has(Permission.TITLE_AMOUNT_APPROVE)) {
            items.add(PendingWorkItem.of(AMOUNT_APPROVALS, "Correções de valor de título para aprovar",
                            queries.amountApprovals(scope, actor.id()))
                    .hint("Confira o motivo, a evidência e os componentes; aprove ou recuse com o motivo.")
                    .route("/titulos/correcoes-de-valor").param("aba", "aprovacoes")
                    .singleRoute(id -> "/titulos/" + id)
                    .build());
        }
        // o limite vale para toda a assessoria: confirma quem aprova, vê todos os credores e não propôs
        if (actor.has(Permission.TITLE_AMOUNT_APPROVE) && accessScope.hasAllCreditors()) {
            items.add(PendingWorkItem.of(THRESHOLD_PROPOSAL, "Aumento do limite de correção de valor para confirmar",
                            queries.thresholdProposal(actor.id()))
                    .hint("Outra pessoa propôs aumentar o limite para aplicar reduções sem segunda pessoa; confirme se concordar.")
                    .route("/titulos/correcoes-de-valor").param("aba", "limite")
                    .build());
        }
        if (actor.has(Permission.TITLE_AMOUNT_ADJUST)) {
            items.add(PendingWorkItem.of(AMOUNT_DIVERGENCES, "Cargas do credor diferentes do valor corrigido",
                            queries.amountDivergences(scope))
                    .hint("Mantenha a correção ou aceite o valor do credor, com o motivo.")
                    .route("/titulos/correcoes-de-valor").param("aba", "divergencias")
                    .singleRoute(id -> "/titulos/" + id)
                    .build());
        }
        return items;
    }
}
