package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.port.out.PaymentPendingWorkQueries;
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
 * Pendências do módulo: revisão por segunda pessoa dos pagamentos externos (quem informou não vê o
 * próprio — não pode aprová-lo) e divergências de conciliação em aberto. Mesmos critérios de
 * {@code PaymentService#reviewQueue} e {@code ReconciliationService#divergences}.
 */
@Component
class PaymentPendingWork implements PendingWorkSource {

    static final String EXTERNAL_REVIEW = "payment.external_review";
    static final String DIVERGENCES = "payment.divergences";

    private final PaymentPendingWorkQueries queries;
    private final AccessScope accessScope;

    PaymentPendingWork(PaymentPendingWorkQueries queries, AccessScope accessScope) {
        this.queries = queries;
        this.accessScope = accessScope;
    }

    @Override
    public String id() {
        return "payment";
    }

    @Override
    public String label() {
        return "Pagamentos";
    }

    @Override
    public boolean appliesTo(Actor actor) {
        return actor.has(Permission.PAYMENT_REVIEW) || actor.has(Permission.RECONCILIATION_MANAGE);
    }

    @Override
    public List<PendingWorkItem> pendingFor(PendingWorkRequest request) {
        Actor actor = request.actor();
        Set<UUID> scope = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        List<PendingWorkItem> items = new ArrayList<>();
        if (actor.has(Permission.PAYMENT_REVIEW)) {
            items.add(PendingWorkItem.of(EXTERNAL_REVIEW, "Pagamentos informados para aprovar",
                            queries.externalReview(scope, actor.id()))
                    .hint("Confira o comprovante e aprove ou recuse.")
                    .route("/pagamentos/aprovacoes").build());
        }
        if (actor.has(Permission.RECONCILIATION_MANAGE)) {
            items.add(PendingWorkItem.of(DIVERGENCES, "Divergências de conciliação", queries.openDivergences(scope))
                    .hint("Vincule ao pagamento, aceite ou descarte.")
                    .route("/pagamentos/divergencias").param("situacao", "OPEN").build());
        }
        return items;
    }
}
