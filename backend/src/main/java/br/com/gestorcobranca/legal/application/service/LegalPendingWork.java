package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.application.port.out.LegalPendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkItem;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRequest;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkSource;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Prazos jurídicos do usuário vencidos ou dentro da janela "perto do prazo", com as duas camadas de
 * escopo do jurídico ({@link LegalAccess}): credores e, para membro de escritório parceiro, só os
 * casos do seu escritório (escritório inativo: nada). O item único abre o caso na aba de prazos.
 */
@Component
class LegalPendingWork implements PendingWorkSource {

    static final String DEADLINES = "legal.deadlines_due";

    private final LegalPendingWorkQueries queries;
    private final LegalAccess access;

    LegalPendingWork(LegalPendingWorkQueries queries, LegalAccess access) {
        this.queries = queries;
        this.access = access;
    }

    @Override
    public String id() {
        return "legal";
    }

    @Override
    public String label() {
        return "Jurídico";
    }

    @Override
    public boolean appliesTo(Actor actor) {
        return actor.has(Permission.LEGAL_MANAGE);
    }

    @Override
    public List<PendingWorkItem> pendingFor(PendingWorkRequest request) {
        Optional<UUID> firm = access.firmFilter();
        PendingWorkCount count = queries.deadlinesDue(access.creditorFilter(), firm.orElse(null), request.actor().id(),
                request.today(), request.dueSoonUntil());
        return List.of(PendingWorkItem.of(DEADLINES, "Prazos jurídicos vencidos ou próximos", count)
                .hint("Cumpra o prazo ou registre o andamento.")
                .route("/juridico")
                .singleRoute(matterId -> "/juridico/casos/" + matterId).build());
    }
}
