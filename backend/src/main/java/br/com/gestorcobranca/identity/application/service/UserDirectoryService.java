package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.identity.domain.model.Team;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.model.UserStatus;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import br.com.gestorcobranca.identity.domain.repository.TeamRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
class UserDirectoryService implements UserDirectory {

    private final UserRepository users;
    private final RoleRepository roles;
    private final TeamRepository teams;
    private final AccessProfileResolver profileResolver;

    UserDirectoryService(UserRepository users, RoleRepository roles, TeamRepository teams,
                         AccessProfileResolver profileResolver) {
        this.users = users;
        this.roles = roles;
        this.teams = teams;
        this.profileResolver = profileResolver;
    }

    @Override
    public Optional<UserSummary> find(UUID userId) {
        return users.findById(userId).map(this::summary);
    }

    @Override
    public List<UserSummary> findAll(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return summaries(users.findAllByIdIn(userIds));
    }

    @Override
    public List<UserSummary> activeUsersWithPermission(String permission) {
        Set<UUID> roleIds = roleIdsWith(Permission.valueOf(permission));
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return summaries(users.findActiveByRoleIds(roleIds));
    }

    private Set<UUID> roleIdsWith(Permission required) {
        return roles.findAll().stream()
                .filter(Role::isActive)
                .filter(r -> r.getPermissions().contains(required))
                .map(Role::getId)
                .collect(Collectors.toSet());
    }

    @Override
    public List<UserSummary> enabledUsers(String permission) {
        List<UserStatus> enabled = List.of(UserStatus.ACTIVE, UserStatus.PENDING_ACTIVATION);
        if (permission == null) {
            return summaries(users.findByStatusIn(enabled));
        }
        Set<UUID> roleIds = roleIdsWith(Permission.valueOf(permission));
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return summaries(users.findByRoleIdsAndStatusIn(roleIds, enabled));
    }

    @Override
    public List<UserSummary> activeTeamMembers(UUID teamId) {
        return teams.findById(teamId)
                .filter(Team::isActive)
                .map(team -> summaries(users.findAllByIdIn(team.getMemberIds()).stream()
                        .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                        .toList()))
                .orElse(List.of());
    }

    @Override
    public Set<UUID> teamsOf(UUID userId) {
        return teams.findByMember(userId).stream().filter(Team::isActive).map(Team::getId).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Optional<String> workEmail(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return users.findById(userId).filter(u -> u.getStatus() == UserStatus.ACTIVE).map(User::getEmail);
    }

    /**
     * Resumos em lote (listas de atribuíveis, a cada busca): perfis e equipes ativas lidos uma vez, sem consulta por
     * usuário; a regra de permissões efetivas continua no {@link AccessProfileResolver}.
     */
    private List<UserSummary> summaries(List<User> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<UUID> roleIds = list.stream().flatMap(u -> u.getRoleIds().stream()).collect(Collectors.toSet());
        Map<UUID, Role> rolesById = roles.findAllByIdIn(roleIds).stream().collect(Collectors.toMap(Role::getId, r -> r));
        Map<UUID, Set<UUID>> teamsByMember = new HashMap<>();
        for (Team team : teams.findAll()) {
            if (team.isActive()) {
                team.getMemberIds().forEach(m -> teamsByMember.computeIfAbsent(m, k -> new HashSet<>()).add(team.getId()));
            }
        }
        return list.stream().map(u -> new UserSummary(u.getId(), u.getName(), u.getStatus() == UserStatus.ACTIVE,
                u.getStatus() == UserStatus.PENDING_ACTIVATION, u.isAllCreditors(), u.getCreditorIds(),
                profileResolver.resolve(u, rolesById).permissionNames(),
                Set.copyOf(teamsByMember.getOrDefault(u.getId(), Set.of())))).toList();
    }

    private UserSummary summary(User user) {
        return new UserSummary(user.getId(), user.getName(), user.getStatus() == UserStatus.ACTIVE,
                user.getStatus() == UserStatus.PENDING_ACTIVATION, user.isAllCreditors(), user.getCreditorIds(),
                profileResolver.resolve(user).permissionNames(), teamsOf(user.getId()));
    }
}
