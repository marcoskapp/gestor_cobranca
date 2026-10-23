package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries;
import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries.TeamStructure;
import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries.WorkforceMember;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Acesso à força de trabalho do identity (perfis, equipes, desligamento). */
@Component
class Workforce {

    private final WorkforceQueries queries;

    Workforce(WorkforceQueries queries) {
        this.queries = queries;
    }

    Optional<WorkforceMember> member(UUID userId) {
        return userId == null ? Optional.empty() : queries.members(List.of(userId)).stream().findFirst();
    }

    Map<UUID, WorkforceMember> members(Collection<UUID> userIds) {
        return queries.members(userIds).stream().collect(Collectors.toMap(WorkforceMember::id, Function.identity()));
    }

    List<TeamStructure> teams() {
        return queries.activeTeams();
    }
}
