package br.com.gestorcobranca.analytics.application.service;

import br.com.gestorcobranca.analytics.application.port.out.AnalyticsReader.TeamRaw;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Escopos aplicados pelo analytics:
 * <ul>
 *   <li><b>Credores</b> ({@link AccessScope}): o filtro pedido é reduzido ao escopo do usuário.</li>
 *   <li><b>Pessoas</b> (mesma regra do incentive): {@code PERFORMANCE_VIEW_ALL} (ou gestão/aprovação de
 *       extratos) = todos; {@code PERFORMANCE_VIEW_TEAM} = a própria pessoa e os membros das equipes
 *       ativas que supervisiona; {@code PERFORMANCE_VIEW_OWN} = só a própria pessoa. Pedido fora do
 *       escopo → 403 com {@code SCOPE_DENIED}; sem nenhuma dessas permissões → 403 com {@code ACCESS_DENIED}.</li>
 * </ul>
 */
@Component
class AnalyticsAccess {

    enum PeopleScope { NONE, OWN, TEAM, ALL }

    private final CurrentActor currentActor;
    private final AccessScope accessScope;
    private final AccessLogger accessLogger;

    AnalyticsAccess(CurrentActor currentActor, AccessScope accessScope, AccessLogger accessLogger) {
        this.currentActor = currentActor;
        this.accessScope = accessScope;
        this.accessLogger = accessLogger;
    }

    Actor actor() {
        return currentActor.get();
    }

    /** Credores efetivos: {@code null} = todos; vazio = nenhum (pedido inteiramente fora do escopo). */
    Set<UUID> creditors(Set<UUID> requested) {
        if (accessScope.hasAllCreditors()) {
            return requested.isEmpty() ? null : requested;
        }
        return accessScope.restrictCreditors(requested);
    }

    PeopleScope peopleScope() {
        Actor actor = actor();
        if (actor.has(Permission.PERFORMANCE_VIEW_ALL) || actor.has(Permission.COMMISSION_STATEMENT_MANAGE)
                || actor.has(Permission.COMMISSION_STATEMENT_APPROVE)) {
            return PeopleScope.ALL;
        }
        if (actor.has(Permission.PERFORMANCE_VIEW_TEAM)) {
            return PeopleScope.TEAM;
        }
        if (actor.has(Permission.PERFORMANCE_VIEW_OWN)) {
            return PeopleScope.OWN;
        }
        return PeopleScope.NONE;
    }

    /** Exige escopo de pessoas (dados por operador/equipe); sem ele, 403 com {@code ACCESS_DENIED}. */
    PeopleScope requirePeopleScope() {
        PeopleScope scope = peopleScope();
        if (scope == PeopleScope.NONE) {
            accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor(),
                    Map.of("permission", "PERFORMANCE_VIEW_OWN", "resource", "analytics_people")));
            throw new ScopeAccessDeniedException();
        }
        return scope;
    }

    /** Pessoas visíveis: {@code null} = todas. */
    Set<UUID> visibleUsers(PeopleScope scope, List<TeamRaw> teams) {
        if (scope == PeopleScope.ALL) {
            return null;
        }
        Set<UUID> users = new HashSet<>();
        users.add(actor().id());
        if (scope == PeopleScope.TEAM) {
            supervisedTeams(teams).forEach(t -> users.addAll(t.members()));
        }
        return users;
    }

    /** Equipes que o ator pode filtrar: {@code null} = todas. */
    Set<UUID> visibleTeams(PeopleScope scope, List<TeamRaw> teams) {
        if (scope == PeopleScope.ALL) {
            return null;
        }
        if (scope == PeopleScope.TEAM) {
            return supervisedTeams(teams).stream().map(TeamRaw::teamId).collect(Collectors.toSet());
        }
        return Set.of();
    }

    private List<TeamRaw> supervisedTeams(List<TeamRaw> teams) {
        UUID me = actor().id();
        return teams.stream().filter(t -> t.active() && me.equals(t.supervisorId())).toList();
    }

    void denyScope(String resource, String detail) {
        accessLogger.log(AccessEvent.of(AccessEventType.SCOPE_DENIED, actor(), Map.of("resource", resource,
                "detail", detail)));
        throw new ScopeAccessDeniedException();
    }

    /** Exige ao menos uma das permissões (403 + {@code ACCESS_DENIED}). */
    void requireAny(String resource, Permission... permissions) {
        Actor actor = actor();
        if (Arrays.stream(permissions).noneMatch(actor::has)) {
            accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor, Map.of("resource", resource,
                    "permission", Arrays.stream(permissions).map(Enum::name).collect(Collectors.joining("|")))));
            throw new ScopeAccessDeniedException();
        }
    }
}
