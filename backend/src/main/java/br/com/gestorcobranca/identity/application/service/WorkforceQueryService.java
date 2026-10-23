package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries;
import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.identity.domain.model.Team;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.model.UserStatus;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import br.com.gestorcobranca.identity.domain.repository.SessionRepository;
import br.com.gestorcobranca.identity.domain.repository.TeamRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.identity.domain.service.LoggedTimeCalculator;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
class WorkforceQueryService implements WorkforceQueries {

    private static final long MAX_RANGE_DAYS = 400;

    private final UserRepository users;
    private final RoleRepository roles;
    private final TeamRepository teams;
    private final SessionRepository sessions;

    WorkforceQueryService(UserRepository users, RoleRepository roles, TeamRepository teams, SessionRepository sessions) {
        this.users = users;
        this.roles = roles;
        this.teams = teams;
        this.sessions = sessions;
    }

    @Override
    public List<WorkforceMember> members(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Role> roleById = roles.findAll().stream().collect(Collectors.toMap(Role::getId, Function.identity()));
        List<Team> activeTeams = teams.findAll().stream().filter(Team::isActive).toList();
        return users.findAllByIdIn(userIds).stream().map(user -> member(user, roleById, activeTeams)).toList();
    }

    @Override
    public List<TeamStructure> activeTeams() {
        return teams.findAll().stream().filter(Team::isActive)
                .map(t -> new TeamStructure(t.getId(), t.getName(), t.getSupervisorId(), Set.copyOf(t.getMemberIds())))
                .toList();
    }

    @Override
    public List<DailyLoggedTime> loggedTime(Collection<UUID> userIds, LocalDate from, LocalDate to) {
        Guard.require(from != null && to != null && !to.isBefore(from), "workforce.range_invalid", "Período inválido");
        Guard.require(ChronoUnit.DAYS.between(from, to) <= MAX_RANGE_DAYS, "workforce.range_too_long",
                "Período máximo de 400 dias");
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        Instant start = from.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        List<LoggedTimeCalculator.Interval> intervals = sessions.findOverlapping(userIds, start, end).stream()
                .map(s -> new LoggedTimeCalculator.Interval(s.getUserId(), s.getStartedAt(), s.activityEnd()))
                .toList();
        List<DailyLoggedTime> result = new ArrayList<>();
        LoggedTimeCalculator.secondsByDay(intervals, start, end, TimeConfig.BUSINESS_ZONE)
                .forEach((user, days) -> days.forEach((day, seconds) -> result.add(new DailyLoggedTime(user, day, seconds))));
        return result;
    }

    private static WorkforceMember member(User user, Map<UUID, Role> roleById, List<Team> activeTeams) {
        Set<String> roleCodes = user.getRoleIds().stream().map(roleById::get)
                .filter(r -> r != null && r.isActive()).map(Role::getCode).collect(Collectors.toUnmodifiableSet());
        Set<UUID> teamIds = activeTeams.stream().filter(t -> t.getMemberIds().contains(user.getId())).map(Team::getId)
                .collect(Collectors.toUnmodifiableSet());
        return new WorkforceMember(user.getId(), user.getName(), user.getStatus() == UserStatus.ACTIVE,
                user.getDisabledAt(), roleCodes, teamIds);
    }
}
