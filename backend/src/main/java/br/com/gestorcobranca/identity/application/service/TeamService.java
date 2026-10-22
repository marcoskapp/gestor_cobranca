package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.TeamUseCases;
import br.com.gestorcobranca.identity.domain.model.Team;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.TeamRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.organization.application.port.in.BusinessUnitUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class TeamService implements TeamUseCases {

    private final TeamRepository teams;
    private final UserRepository users;
    private final BusinessUnitUseCases businessUnits;
    private final AuditTrail auditTrail;

    TeamService(TeamRepository teams, UserRepository users, BusinessUnitUseCases businessUnits, AuditTrail auditTrail) {
        this.teams = teams;
        this.users = users;
        this.businessUnits = businessUnits;
        this.auditTrail = auditTrail;
    }

    @Override
    public TeamView create(TeamCommand command) {
        validate(command);
        Team team = new Team(command.name(), command.businessUnitId(), command.supervisorId());
        team.defineMembers(command.memberIds());
        teams.save(team);
        auditTrail.record(AuditEntry.of("team.created", "team", team.getId())
                .created("name", team.getName())
                .created("supervisorId", team.getSupervisorId())
                .created("members", team.getMemberIds().size()));
        return view(team);
    }

    @Override
    public TeamView update(UUID teamId, TeamCommand command) {
        Team team = load(teamId);
        validate(command);
        AuditEntry audit = AuditEntry.of("team.updated", "team", teamId)
                .change("name", team.getName(), command.name())
                .change("supervisorId", team.getSupervisorId(), command.supervisorId())
                .change("members", String.valueOf(team.getMemberIds()), String.valueOf(command.memberIds()));
        team.update(command.name(), command.businessUnitId(), command.supervisorId());
        team.defineMembers(command.memberIds());
        teams.save(team);
        auditTrail.record(audit);
        return view(team);
    }

    @Override
    public TeamView changeStatus(UUID teamId, boolean active) {
        Team team = load(teamId);
        boolean previous = team.isActive();
        if (active) {
            team.activate();
        } else {
            team.deactivate();
        }
        teams.save(team);
        auditTrail.record(AuditEntry.of("team.status_changed", "team", teamId).change("active", previous, active));
        return view(team);
    }

    @Override
    @Transactional(readOnly = true)
    public TeamView get(UUID teamId) {
        return view(load(teamId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TeamView> list() {
        return teams.findAll().stream().sorted(Comparator.comparing(Team::getName)).map(TeamService::view).toList();
    }

    private void validate(TeamCommand command) {
        if (command.businessUnitId() != null) {
            businessUnits.get(command.businessUnitId());
        }
        Set<UUID> referenced = new HashSet<>(command.memberIds() == null ? Set.of() : command.memberIds());
        if (command.supervisorId() != null) {
            referenced.add(command.supervisorId());
        }
        if (!referenced.isEmpty()) {
            List<User> found = users.findAllByIdIn(referenced);
            if (found.size() != referenced.size()) {
                throw new BusinessRuleException("team.user_invalid", "Usuário inexistente na equipe");
            }
        }
    }

    private Team load(UUID teamId) {
        return teams.findById(teamId).orElseThrow(() -> ResourceNotFoundException.of("team", teamId));
    }

    private static TeamView view(Team team) {
        return new TeamView(team.getId(), team.getName(), team.getBusinessUnitId(), team.getSupervisorId(), team.isActive(),
                team.getMemberIds());
    }
}
