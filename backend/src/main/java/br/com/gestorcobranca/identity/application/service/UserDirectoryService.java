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
import java.util.List;
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
        return users.findAllByIdIn(userIds).stream().map(this::summary).toList();
    }

    @Override
    public List<UserSummary> activeUsersWithPermission(String permission) {
        Permission required = Permission.valueOf(permission);
        Set<UUID> roleIds = roles.findAll().stream()
                .filter(Role::isActive)
                .filter(r -> r.getPermissions().contains(required))
                .map(Role::getId)
                .collect(Collectors.toSet());
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return users.findActiveByRoleIds(roleIds).stream().map(this::summary).toList();
    }

    @Override
    public List<UserSummary> activeTeamMembers(UUID teamId) {
        return teams.findById(teamId)
                .filter(Team::isActive)
                .map(team -> users.findAllByIdIn(team.getMemberIds()).stream()
                        .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                        .map(this::summary)
                        .toList())
                .orElse(List.of());
    }

    @Override
    public Set<UUID> teamsOf(UUID userId) {
        return teams.findByMember(userId).stream().filter(Team::isActive).map(Team::getId).collect(Collectors.toUnmodifiableSet());
    }

    private UserSummary summary(User user) {
        return new UserSummary(user.getId(), user.getName(), user.getStatus() == UserStatus.ACTIVE, user.isAllCreditors(),
                user.getCreditorIds(), profileResolver.resolve(user).permissionNames(), teamsOf(user.getId()));
    }
}
