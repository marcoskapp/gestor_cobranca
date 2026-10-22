package br.com.gestorcobranca.identity.application.port.in;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Consulta de usuários e equipes para outros módulos (distribuição de casos, aprovações,
 * relatórios por operador). Retorna somente dados não sensíveis.
 */
public interface UserDirectory {

    Optional<UserSummary> find(UUID userId);

    List<UserSummary> findAll(Collection<UUID> userIds);

    /** Usuários ativos que possuem a permissão (ex.: CASE_WORK para distribuição). */
    List<UserSummary> activeUsersWithPermission(String permission);

    /** Membros ativos de uma equipe. */
    List<UserSummary> activeTeamMembers(UUID teamId);

    /** Equipes ativas das quais o usuário faz parte. */
    Set<UUID> teamsOf(UUID userId);

    /**
     * @param permissions  permissões efetivas (união dos perfis ativos)
     * @param teamIds      equipes ativas do usuário
     */
    record UserSummary(UUID id, String name, boolean active, boolean allCreditors, Set<UUID> creditorIds,
                       Set<String> permissions, Set<UUID> teamIds) {

        public boolean canAccessCreditor(UUID creditorId) {
            return allCreditors || creditorIds.contains(creditorId);
        }
    }
}
