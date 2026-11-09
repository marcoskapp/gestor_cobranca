package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.port.out.CompliancePendingWorkQueries;
import br.com.gestorcobranca.shared.config.TimeConfig;
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
 * Solicitações de titulares (LGPD art. 18) perto do prazo legal: abertas com prazo até o fim da
 * janela "perto do prazo" ({@code app.pending-work.due-soon-days}), inclusive as vencidas. O
 * atendimento alcança todos os credores do titular, então só quem tem acesso a todos os credores vê
 * (mesma regra de {@code DataSubjectRequestService}). É fila da equipe de atendimento ao titular, não
 * do responsável atribuído.
 * <p>
 * Sugestões de marcador de vulnerabilidade do assistente aguardando confirmação: de quem registra marcadores, no
 * escopo de credores do ator (credor do contato em que houve a detecção).
 */
@Component
class CompliancePendingWork implements PendingWorkSource {

    static final String DATA_SUBJECT_REQUESTS = "compliance.data_subject_requests";
    static final String VULNERABILITY_SUGGESTIONS = "compliance.vulnerability_suggestions";

    private final CompliancePendingWorkQueries queries;
    private final AccessScope accessScope;

    CompliancePendingWork(CompliancePendingWorkQueries queries, AccessScope accessScope) {
        this.queries = queries;
        this.accessScope = accessScope;
    }

    @Override
    public String id() {
        return "compliance";
    }

    @Override
    public String label() {
        return "Privacidade e conformidade";
    }

    @Override
    public boolean appliesTo(Actor actor) {
        return dataSubject(actor) || vulnerability(actor);
    }

    @Override
    public List<PendingWorkItem> pendingFor(PendingWorkRequest request) {
        Actor actor = request.actor();
        List<PendingWorkItem> items = new ArrayList<>();
        if (dataSubject(actor)) {
            PendingWorkCount count = queries.dataSubjectRequestsDueSoon(request.now(), request.startOfTomorrow(),
                    request.dueSoonUntil().plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant());
            items.add(PendingWorkItem.of(DATA_SUBJECT_REQUESTS, "Solicitações de titulares perto do prazo", count)
                    .hint("Responda dentro do prazo legal da LGPD.")
                    .route("/compliance/solicitacoes-titulares").param("vencidas", count.overdue() > 0 ? "true" : null)
                    .singleRoute(id -> "/compliance/solicitacoes-titulares/" + id).build());
        }
        if (vulnerability(actor)) {
            Set<UUID> scope = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
            PendingWorkCount suggestions = queries.vulnerabilitySuggestionsPending(scope);
            items.add(PendingWorkItem.of(VULNERABILITY_SUGGESTIONS,
                            "Sugestões de atenção especial (vulnerabilidade) a confirmar", suggestions)
                    .hint("Confira a conversa e registre a categoria do marcador, ou dispense a sugestão.")
                    .route("/compliance/vulnerabilidade").param("aba", "sugestoes")
                    .build());
        }
        return items;
    }

    private static boolean dataSubject(Actor actor) {
        return actor.has(Permission.DATA_SUBJECT_REQUEST_MANAGE) && actor.creditorScope().all();
    }

    private static boolean vulnerability(Actor actor) {
        return actor.has(Permission.VULNERABILITY_REGISTER) || actor.has(Permission.VULNERABILITY_MANAGE);
    }
}
