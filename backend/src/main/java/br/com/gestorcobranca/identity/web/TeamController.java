package br.com.gestorcobranca.identity.web;

import br.com.gestorcobranca.identity.application.port.in.TeamUseCases;
import br.com.gestorcobranca.identity.application.port.in.TeamUseCases.TeamCommand;
import br.com.gestorcobranca.identity.application.port.in.TeamUseCases.TeamView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.V1 + "/teams")
class TeamController {

    private final TeamUseCases teams;

    TeamController(TeamUseCases teams) {
        this.teams = teams;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ACTOR_USER')")
    List<TeamView> list() {
        return teams.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ACTOR_USER')")
    TeamView get(@PathVariable UUID id) {
        return teams.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('TEAM_MANAGE')")
    TeamView create(@Valid @RequestBody TeamRequest request) {
        return teams.create(request.toCommand());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('TEAM_MANAGE')")
    TeamView update(@PathVariable UUID id, @Valid @RequestBody TeamRequest request) {
        return teams.update(id, request.toCommand());
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('TEAM_MANAGE')")
    TeamView activate(@PathVariable UUID id) {
        return teams.changeStatus(id, true);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('TEAM_MANAGE')")
    TeamView deactivate(@PathVariable UUID id) {
        return teams.changeStatus(id, false);
    }

    record TeamRequest(@NotBlank @Size(max = 120) String name, UUID businessUnitId, UUID supervisorId,
                       Set<UUID> memberIds) {

        TeamCommand toCommand() {
            return new TeamCommand(name, businessUnitId, supervisorId, memberIds);
        }
    }
}
