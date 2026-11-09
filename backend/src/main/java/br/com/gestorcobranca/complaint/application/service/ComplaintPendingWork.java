package br.com.gestorcobranca.complaint.application.service;

import br.com.gestorcobranca.complaint.application.port.out.ComplaintQueries;
import br.com.gestorcobranca.complaint.domain.service.ComplaintDeadlines;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
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
 * Reclamações aguardando resposta perto do prazo ou vencidas ("Minhas pendências"): as do próprio responsável e, para
 * quem trata reclamações, as sem responsável. "Perto do prazo" = dentro do aviso do canal (padrão
 * {@value ComplaintDeadlines#DEFAULT_WARNING_DAYS} dias antes do prazo). Escopo por credor do ator.
 */
@Component
class ComplaintPendingWork implements PendingWorkSource {

    static final String MINE = "complaint.mine_due";
    static final String UNASSIGNED = "complaint.unassigned_due";

    private final ComplaintQueries queries;
    private final AccessScope accessScope;

    ComplaintPendingWork(ComplaintQueries queries, AccessScope accessScope) {
        this.queries = queries;
        this.accessScope = accessScope;
    }

    @Override
    public String id() {
        return "complaint";
    }

    @Override
    public String label() {
        return "Reclamações";
    }

    @Override
    public boolean appliesTo(Actor actor) {
        return actor.has(Permission.COMPLAINT_MANAGE);
    }

    @Override
    public List<PendingWorkItem> pendingFor(PendingWorkRequest request) {
        Set<UUID> scope = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        List<PendingWorkItem> items = new ArrayList<>();
        PendingWorkCount mine = queries.dueSoon(scope, request.actor().id(), false, request.today());
        items.add(PendingWorkItem.of(MINE, "Suas reclamações perto do prazo ou vencidas", mine)
                .hint("Responda ao canal (Procon, consumidor.gov.br, Bacen, ouvidoria) dentro do prazo.")
                .route("/compliance/reclamacoes").param("responsavel", "me").param("prazo", "soon")
                .singleRoute(id -> "/compliance/reclamacoes/" + id).build());
        PendingWorkCount unassigned = queries.dueSoon(scope, null, true, request.today());
        items.add(PendingWorkItem.of(UNASSIGNED, "Reclamações sem responsável perto do prazo", unassigned)
                .hint("Assuma ou atribua um responsável.")
                .route("/compliance/reclamacoes").param("sem-responsavel", "1").param("prazo", "soon")
                .singleRoute(id -> "/compliance/reclamacoes/" + id).build());
        return items;
    }
}
