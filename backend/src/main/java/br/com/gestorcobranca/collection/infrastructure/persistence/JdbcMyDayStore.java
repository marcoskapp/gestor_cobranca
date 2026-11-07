package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.application.port.in.MyDayQueries.QueueDay;
import br.com.gestorcobranca.collection.application.port.out.MyDayStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Contagens do "Seu dia". Índices: {@code ix_case_assignee} (casos ativos por operador),
 * {@code ix_task_agenda} (retornos em aberto por responsável), {@code uk_promise_pending} (promessa
 * pendente do caso), {@code ix_interaction_operator} (interações do operador no dia),
 * {@code ix_promise_case} e {@code ix_case_queue_available} (casos aguardando na fila).
 */
@Component
class JdbcMyDayStore implements MyDayStore {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcMyDayStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long activeCases(Set<UUID> creditorScope, Set<UUID> userIds) {
        if (empty(creditorScope) || userIds.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("users", userIds);
        String sql = "select count(*) from collection.collection_case c where c.status <> 'CLOSED'"
                + " and c.assignee_user_id in (:users)" + scope("c", creditorScope, params);
        return count(sql, params);
    }

    @Override
    public TaskCounts tasks(Set<UUID> creditorScope, Set<UUID> userIds, Instant now, Instant startOfTomorrow,
                            Instant missedSince) {
        if (empty(creditorScope) || userIds.isEmpty()) {
            return new TaskCounts(0, 0);
        }
        MapSqlParameterSource params = TaskDueSql.params(now, startOfTomorrow, missedSince).addValue("users", userIds);
        // mesma regra de "Minhas pendências" (TaskDueSql)
        String sql = "select count(*) filter (where " + TaskDueSql.DUE_TODAY + ") as today, count(*) filter (where "
                + TaskDueSql.OVERDUE + ") as overdue" + TaskDueSql.fromOpenUntilTomorrow("t.assignee_user_id in (:users)")
                + scope("t", creditorScope, params);
        return jdbc.queryForObject(sql, params, (rs, i) -> new TaskCounts(rs.getLong("today"), rs.getLong("overdue")));
    }

    @Override
    public PromiseCounts promises(Set<UUID> creditorScope, Set<UUID> userIds, LocalDate today) {
        if (empty(creditorScope) || userIds.isEmpty()) {
            return new PromiseCounts(0, 0);
        }
        MapSqlParameterSource params = new MapSqlParameterSource("users", userIds)
                .addValue("today", Date.valueOf(today));
        String sql = """
                select count(*) filter (where p.due_date = :today) as due_today,
                       count(*) filter (where p.due_date < :today) as awaiting
                  from collection.collection_case c
                  join collection.promise p on p.case_id = c.id and p.status = 'PENDING'
                 where c.status <> 'CLOSED' and c.assignee_user_id in (:users) and p.due_date <= :today
                """ + scope("c", creditorScope, params);
        return jdbc.queryForObject(sql, params,
                (rs, i) -> new PromiseCounts(rs.getLong("due_today"), rs.getLong("awaiting")));
    }

    @Override
    public OwnCounts ownResults(Set<UUID> creditorScope, UUID userId, Instant since) {
        if (empty(creditorScope)) {
            return new OwnCounts(0, 0, 0);
        }
        MapSqlParameterSource params = new MapSqlParameterSource("me", userId).addValue("since", Timestamp.from(since));
        String scope = scope("i", creditorScope, params);
        String sql = """
                select count(*) as interactions,
                       count(*) filter (where i.effective_contact) as effective,
                       (select count(*) from collection.interaction i
                          join collection.promise p on p.case_id = i.case_id and p.origin_interaction_id = i.id
                         where i.operator_id = :me and i.started_at >= :since %s) as promises
                  from collection.interaction i
                 where i.operator_id = :me and i.started_at >= :since
                """.formatted(scope) + scope;
        return jdbc.queryForObject(sql, params, (rs, i) -> new OwnCounts(rs.getLong("interactions"),
                rs.getLong("effective"), rs.getLong("promises")));
    }

    @Override
    public List<QueueDay> queues(Set<UUID> creditorScope, Set<UUID> teamIds, Instant now) {
        if (empty(creditorScope) || teamIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("teams", teamIds).addValue("now", Timestamp.from(now));
        StringBuilder sql = new StringBuilder("""
                select q.id, q.name,
                       (select count(*) from collection.collection_case c
                         where c.queue_id = q.id and c.assignee_user_id is null and c.eligible and c.owner_type = 'QUEUE'
                           and c.status in ('OPEN', 'IN_NEGOTIATION', 'PROMISE')%1$s) as waiting,
                       (select count(*) from collection.collection_case c
                         where c.queue_id = q.id and c.assignee_user_id is null and c.eligible and c.owner_type = 'QUEUE'
                           and c.status in ('OPEN', 'IN_NEGOTIATION', 'PROMISE')
                           and c.next_action_at < :now%1$s) as overdue
                  from collection.queue q
                 where q.active and exists (select 1 from collection.queue_team qt
                                             where qt.queue_id = q.id and qt.team_id in (:teams))
                """);
        String caseScope = "";
        if (creditorScope != null) {
            params.addValue("scope", creditorScope);
            caseScope = " and c.creditor_id in (:scope)";
            sql.append(" and (q.creditor_id is null or q.creditor_id in (:scope))");
        }
        sql.append(" order by q.priority, q.name");
        return jdbc.query(sql.toString().formatted(caseScope), params, (rs, i) -> new QueueDay(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("waiting"), rs.getLong("overdue")));
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long n = jdbc.queryForObject(sql, params, Long.class);
        return n == null ? 0 : n;
    }

    private static boolean empty(Set<UUID> creditorScope) {
        return creditorScope != null && creditorScope.isEmpty();
    }

    private static String scope(String alias, Set<UUID> creditorScope, MapSqlParameterSource params) {
        if (creditorScope == null) {
            return "";
        }
        params.addValue("scope", creditorScope);
        return " and " + alias + ".creditor_id in (:scope)";
    }
}
