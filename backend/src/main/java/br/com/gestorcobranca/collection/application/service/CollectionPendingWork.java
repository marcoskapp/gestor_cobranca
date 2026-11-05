package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.out.CollectionPendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkItem;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRequest;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkSource;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pendências da operação: retornos agendados do próprio usuário vencidos ou para hoje (a agenda; o
 * item único abre a estação no caso) e contestações com prazo de resposta vencido ou próximo.
 */
@Component
class CollectionPendingWork implements PendingWorkSource {

    static final String CALLBACKS = "collection.callbacks_due";
    static final String DISPUTES = "collection.disputes_due";
    static final String MY_DISPUTES = "collection.disputes_mine";
    static final String DEBTOR_NOTICES = "collection.dispute_debtor_notices";
    static final String ADJUSTMENTS = "collection.dispute_adjustments";
    /** Retornos perdidos continuam na agenda ("Atrasados") por 30 dias. */
    static final Duration MISSED_WINDOW = Duration.ofDays(30);

    private final CollectionPendingWorkQueries queries;
    private final AccessScope accessScope;

    CollectionPendingWork(CollectionPendingWorkQueries queries, AccessScope accessScope) {
        this.queries = queries;
        this.accessScope = accessScope;
    }

    @Override
    public String id() {
        return "collection";
    }

    @Override
    public String label() {
        return "Cobrança";
    }

    @Override
    public boolean appliesTo(Actor actor) {
        return actor.has(Permission.CASE_WORK) || actor.has(Permission.TASK_MANAGE) || actor.has(Permission.DISPUTE_MANAGE)
                || actor.has(Permission.DISPUTE_WORK);
    }

    @Override
    public List<PendingWorkItem> pendingFor(PendingWorkRequest request) {
        Actor actor = request.actor();
        Set<UUID> scope = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        List<PendingWorkItem> items = new ArrayList<>();
        if (actor.has(Permission.CASE_WORK) || actor.has(Permission.TASK_MANAGE)) {
            PendingWorkCount tasks = queries.tasksDue(scope, actor.id(), request.now(), request.startOfTomorrow(),
                    request.startOfToday().minus(MISSED_WINDOW));
            PendingWorkItem.Builder item = PendingWorkItem.of(CALLBACKS, "Retornos agendados vencidos ou para hoje", tasks)
                    .hint("Ligue no horário combinado ou reagende.")
                    .route("/cobranca/agenda").param("periodo", tasks.overdue() > 0 ? "late" : "today");
            if (actor.has(Permission.CASE_WORK)) {
                item.singleRoute(caseId -> "/cobranca/casos/" + caseId);
            }
            items.add(item.build());
        }
        boolean manage = actor.has(Permission.DISPUTE_MANAGE);
        if (manage || actor.has(Permission.DISPUTE_WORK)) {
            PendingWorkCount mine = queries.disputesOfAssignee(scope, actor.id(), request.today(), request.dueSoonUntil());
            items.add(PendingWorkItem.of(MY_DISPUTES, "Suas contestações com prazo próximo ou vencido", mine)
                    .hint("Peça os documentos ao credor, analise e resolva dentro do prazo.")
                    .route("/cobranca/contestacoes").param("responsavel", "me")
                    .singleRoute(id -> "/cobranca/contestacoes/" + id)
                    .build());
        }
        if (manage) {
            PendingWorkCount supervision = queries.disputesForSupervision(scope, request.today());
            items.add(PendingWorkItem.of(DISPUTES, "Contestações sem responsável ou com prazo vencido", supervision)
                    .hint("Atribua um responsável ou redistribua as vencidas.")
                    .route("/cobranca/contestacoes").param("fila", "supervisao")
                    .singleRoute(id -> "/cobranca/contestacoes/" + id)
                    .build());
        }
        if (manage || actor.has(Permission.DISPUTE_WORK)) {
            UUID owner = manage ? null : actor.id();
            PendingWorkCount notices = queries.debtorNoticesPending(scope, owner);
            PendingWorkItem.Builder notice = PendingWorkItem.of(DEBTOR_NOTICES,
                            "Contestações resolvidas sem resposta ao devedor", notices)
                    .hint("Envie ao devedor a resposta fundamentada do resultado.")
                    .route("/cobranca/contestacoes").param("resposta", "pendente")
                    .singleRoute(id -> "/cobranca/contestacoes/" + id);
            PendingWorkCount adjustments = queries.adjustmentsPending(scope, owner);
            PendingWorkItem.Builder adjustment = PendingWorkItem.of(ADJUSTMENTS,
                            "Ajustes de título pendentes após contestação procedente", adjustments)
                    .hint("Faça o ajuste sugerido no título ou dispense com o motivo.")
                    .route("/cobranca/contestacoes").param("ajuste", "pendente")
                    .singleRoute(id -> "/cobranca/contestacoes/" + id);
            if (!manage) {
                notice.param("responsavel", "me");
                adjustment.param("responsavel", "me");
            }
            items.add(notice.build());
            items.add(adjustment.build());
        }
        return items;
    }
}
