package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.port.out.CompliancePendingWorkQueries;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkItem;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRequest;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkSource;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Solicitações de titulares (LGPD art. 18) perto do prazo legal: abertas com prazo até o fim da
 * janela "perto do prazo" ({@code app.pending-work.due-soon-days}), inclusive as vencidas. O
 * atendimento alcança todos os credores do titular, então só quem tem acesso a todos os credores vê
 * (mesma regra de {@code DataSubjectRequestService}). É fila da equipe de atendimento ao titular, não
 * do responsável atribuído.
 */
@Component
class CompliancePendingWork implements PendingWorkSource {

    static final String DATA_SUBJECT_REQUESTS = "compliance.data_subject_requests";

    private final CompliancePendingWorkQueries queries;

    CompliancePendingWork(CompliancePendingWorkQueries queries) {
        this.queries = queries;
    }

    @Override
    public String id() {
        return "compliance";
    }

    @Override
    public String label() {
        return "Privacidade (LGPD)";
    }

    @Override
    public boolean appliesTo(Actor actor) {
        return actor.has(Permission.DATA_SUBJECT_REQUEST_MANAGE) && actor.creditorScope().all();
    }

    @Override
    public List<PendingWorkItem> pendingFor(PendingWorkRequest request) {
        PendingWorkCount count = queries.dataSubjectRequestsDueSoon(request.now(), request.startOfTomorrow(),
                request.dueSoonUntil().plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant());
        return List.of(PendingWorkItem.of(DATA_SUBJECT_REQUESTS, "Solicitações de titulares perto do prazo", count)
                .hint("Responda dentro do prazo legal da LGPD.")
                .route("/compliance/solicitacoes-titulares").param("vencidas", count.overdue() > 0 ? "true" : null)
                .singleRoute(id -> "/compliance/solicitacoes-titulares/" + id).build());
    }
}
