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

    /**
     * Usuários habilitados — ativos ou aguardando ativação (nunca os desativados) —, para atribuições que podem ser
     * preparadas antes do primeiro acesso (ex.: alçada de negociação e meta no onboarding).
     *
     * @param permission permissão exigida (ex.: CASE_WORK); nula = qualquer usuário habilitado
     */
    List<UserSummary> enabledUsers(String permission);

    /** Membros ativos de uma equipe. */
    List<UserSummary> activeTeamMembers(UUID teamId);

    /** Equipes ativas das quais o usuário faz parte. */
    Set<UUID> teamsOf(UUID userId);

    /**
     * E-mail corporativo de um usuário ativo da assessoria, para comunicações operacionais em nome dele (ex.: resposta
     * e cópia de um pedido de contestação ao credor). Vazio para usuário inexistente ou inativo. Nunca registrar em log.
     */
    Optional<String> workEmail(UUID userId);

    /**
     * @param active            situação ativa (autentica)
     * @param pendingActivation criado e aguardando a definição de senha pelo link de ativação
     * @param permissions       permissões efetivas (união dos perfis ativos)
     * @param teamIds           equipes ativas do usuário
     */
    record UserSummary(UUID id, String name, boolean active, boolean pendingActivation, boolean allCreditors,
                       Set<UUID> creditorIds, Set<String> permissions, Set<UUID> teamIds) {

        public boolean canAccessCreditor(UUID creditorId) {
            return allCreditors || creditorIds.contains(creditorId);
        }

        /** Ativo ou aguardando ativação (não desativado). */
        public boolean enabled() {
            return active || pendingActivation;
        }
    }
}
