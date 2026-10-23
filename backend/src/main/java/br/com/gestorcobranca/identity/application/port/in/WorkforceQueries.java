package br.com.gestorcobranca.identity.application.port.in;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Consultas sobre a força de trabalho para módulos de gestão de desempenho (ex.: incentive):
 * perfis e equipes dos usuários, situação de desligamento, estrutura das equipes e tempo logado
 * a partir das sessões autenticadas. Retorna somente dados não sensíveis; sem e-mail ou IP.
 */
public interface WorkforceQueries {

    /** Usuários informados, inclusive desativados (o desligamento afeta a remuneração variável). */
    List<WorkforceMember> members(Collection<UUID> userIds);

    /** Equipes ativas com supervisor e membros cadastrados (ativos ou não; ver {@link WorkforceMember#active()}). */
    List<TeamStructure> activeTeams();

    /**
     * Tempo logado por usuário e dia (fuso de operação), entre as datas inclusive. Definição: união
     * dos intervalos das sessões do usuário, de {@code startedAt} até a última atividade registrada
     * ({@code lastSeenAt}, limitada pelo encerramento); sessões simultâneas não são contadas em
     * dobro. Dias sem sessão não aparecem.
     */
    List<DailyLoggedTime> loggedTime(Collection<UUID> userIds, LocalDate from, LocalDate to);

    /**
     * @param disabledAt instante da desativação (desligamento), null se ativo ou pendente de ativação
     * @param roleCodes  códigos dos perfis ativos atribuídos
     * @param teamIds    equipes ativas das quais é membro
     */
    record WorkforceMember(UUID id, String name, boolean active, Instant disabledAt, Set<String> roleCodes,
                           Set<UUID> teamIds) {
    }

    record TeamStructure(UUID id, String name, UUID supervisorId, Set<UUID> memberIds) {
    }

    record DailyLoggedTime(UUID userId, LocalDate date, long seconds) {
    }
}
