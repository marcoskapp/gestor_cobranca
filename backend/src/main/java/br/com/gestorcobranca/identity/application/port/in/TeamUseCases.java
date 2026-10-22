package br.com.gestorcobranca.identity.application.port.in;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface TeamUseCases {

    TeamView create(TeamCommand command);

    TeamView update(UUID teamId, TeamCommand command);

    TeamView changeStatus(UUID teamId, boolean active);

    TeamView get(UUID teamId);

    List<TeamView> list();

    record TeamCommand(String name, UUID businessUnitId, UUID supervisorId, Set<UUID> memberIds) {
    }

    record TeamView(UUID id, String name, UUID businessUnitId, UUID supervisorId, boolean active, Set<UUID> memberIds) {
    }
}
