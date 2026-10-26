package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.scoring.application.port.out.ScoringPendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkItem;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRequest;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkSource;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Modelo desafiante aguardando promoção ou rejeição. A governança de modelos alcança todos os
 * credores ({@code requireAllCreditors}), então só quem tem todos os credores vê; quem treinou não vê.
 */
@Component
class ScoringPendingWork implements PendingWorkSource {

    static final String CHALLENGER = "scoring.challenger_decision";

    private final ScoringPendingWorkQueries queries;

    ScoringPendingWork(ScoringPendingWorkQueries queries) {
        this.queries = queries;
    }

    @Override
    public String id() {
        return "scoring";
    }

    @Override
    public String label() {
        return "Score de propensão";
    }

    @Override
    public boolean appliesTo(Actor actor) {
        return actor.has(Permission.SCORING_MANAGE) && actor.creditorScope().all();
    }

    @Override
    public List<PendingWorkItem> pendingFor(PendingWorkRequest request) {
        return List.of(PendingWorkItem.of(CHALLENGER, "Modelo desafiante aguardando promoção",
                        queries.challengerAwaitingDecision(request.actor().id()))
                .hint("Compare com o campeão e promova ou rejeite.")
                .route("/score/modelos").singleRoute(id -> "/score/modelos/" + id).build());
    }
}
