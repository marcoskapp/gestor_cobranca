package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries;
import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries.TeamStructure;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Escopo de visibilidade de desempenho e remuneração (§7): próprio, equipes supervisionadas ou
 * todos. Gestão e aprovação de extratos implicam visão de todos. Toda consulta a remuneração de
 * outra pessoa gera {@link AccessEventType#COMPENSATION_DATA_VIEWED}; tentativa fora do escopo
 * gera {@link AccessEventType#SCOPE_DENIED} e 403.
 */
@Component
class IncentiveAccess {

    enum Scope { OWN, TEAM, ALL }

    private final CurrentActor currentActor;
    private final WorkforceQueries workforce;
    private final AccessLogger accessLogger;

    IncentiveAccess(CurrentActor currentActor, WorkforceQueries workforce, AccessLogger accessLogger) {
        this.currentActor = currentActor;
        this.workforce = workforce;
        this.accessLogger = accessLogger;
    }

    Actor actor() {
        return currentActor.get();
    }

    Scope scope() {
        Actor actor = actor();
        if (actor.has(Permission.PERFORMANCE_VIEW_ALL) || actor.has(Permission.COMMISSION_STATEMENT_MANAGE)
                || actor.has(Permission.COMMISSION_STATEMENT_APPROVE)) {
            return Scope.ALL;
        }
        if (actor.has(Permission.PERFORMANCE_VIEW_TEAM)) {
            return Scope.TEAM;
        }
        if (actor.has(Permission.PERFORMANCE_VIEW_OWN)) {
            return Scope.OWN;
        }
        accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor, Map.of("permission", "PERFORMANCE_VIEW_OWN")));
        throw new ScopeAccessDeniedException();
    }

    /** Usuários visíveis ao ator; null = todos. */
    Set<UUID> visibleUsers() {
        Scope scope = scope();
        if (scope == Scope.ALL) {
            return null;
        }
        Set<UUID> users = new HashSet<>();
        users.add(actor().id());
        if (scope == Scope.TEAM) {
            for (TeamStructure team : workforce.activeTeams()) {
                if (actor().id().equals(team.supervisorId())) {
                    users.addAll(team.memberIds());
                }
            }
        }
        return users;
    }

    /** Equipes cujos membros o ator pode ver; null = todas. */
    Set<UUID> visibleTeams() {
        Scope scope = scope();
        if (scope == Scope.ALL) {
            return null;
        }
        Set<UUID> teams = new HashSet<>();
        if (scope == Scope.TEAM) {
            workforce.activeTeams().stream().filter(t -> actor().id().equals(t.supervisorId()))
                    .forEach(t -> teams.add(t.id()));
        }
        return teams;
    }

    /** Nega (403, log de acesso) se o usuário estiver fora do escopo do ator. */
    void requireVisible(UUID userId, String resource) {
        Set<UUID> visible = visibleUsers();
        if (visible != null && !visible.contains(userId)) {
            accessLogger.log(AccessEvent.of(AccessEventType.SCOPE_DENIED, actor(),
                    Map.of("resource", resource, "targetUserId", String.valueOf(userId))));
            throw new ScopeAccessDeniedException();
        }
    }

    /** Registra a consulta à remuneração de outra pessoa. */
    void logCompensationView(UUID userId, String resource, UUID resourceId) {
        Actor actor = actor();
        if (!actor.isSystem() && !actor.id().equals(userId)) {
            accessLogger.log(AccessEvent.of(AccessEventType.COMPENSATION_DATA_VIEWED, actor,
                    Map.of("resource", resource, "resourceId", String.valueOf(resourceId),
                            "targetUserId", String.valueOf(userId))));
        }
    }

    /** Consulta agregada de remuneração de vários negociadores (lista, simulação). */
    void logCompensationListing(String resource, String detail) {
        Actor actor = actor();
        if (!actor.isSystem()) {
            accessLogger.log(AccessEvent.of(AccessEventType.COMPENSATION_DATA_VIEWED, actor,
                    Map.of("resource", resource, "scope", detail)));
        }
    }

    void requirePermission(Permission permission) {
        Actor actor = actor();
        if (!actor.has(permission)) {
            accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor, Map.of("permission", permission.name())));
            throw new ScopeAccessDeniedException();
        }
    }
}
