package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Quem pode ver o nível de desempenho de cada pessoa (dado pessoal do empregado — distribuicao-por-desempenho.md §6
 * e §7): {@code PERFORMANCE_VIEW_ALL} vê todos; {@code PERFORMANCE_VIEW_TEAM}, os membros das equipes que supervisiona
 * e o próprio; {@code PERFORMANCE_VIEW_OWN}, só o próprio (§10-Q4). Mesma regra de escopo do incentive
 * ({@code IncentiveAccess}). Sem visão, as telas mostram só quantidades por nível.
 */
@Component
class TierVisibility {

    private final CurrentActor currentActor;
    private final WorkforceQueries workforce;

    TierVisibility(CurrentActor currentActor, WorkforceQueries workforce) {
        this.currentActor = currentActor;
        this.workforce = workforce;
    }

    /** Pessoas cujo nível o ator corrente pode ver. */
    Predicate<UUID> visibleUsers() {
        Actor actor = currentActor.get();
        if (actor.isSystem() || actor.has(Permission.PERFORMANCE_VIEW_ALL)) {
            return user -> true;
        }
        Set<UUID> users = new HashSet<>();
        if (actor.has(Permission.PERFORMANCE_VIEW_TEAM)) {
            workforce.activeTeams().stream().filter(t -> actor.id().equals(t.supervisorId()))
                    .forEach(t -> users.addAll(t.memberIds()));
            users.add(actor.id());
        }
        if (actor.has(Permission.PERFORMANCE_VIEW_OWN)) {
            users.add(actor.id());
        }
        return users::contains;
    }
}
