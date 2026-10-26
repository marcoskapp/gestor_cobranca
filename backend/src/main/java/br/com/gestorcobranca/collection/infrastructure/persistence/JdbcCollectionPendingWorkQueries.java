package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.application.port.out.CollectionPendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRows;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Índices: {@code ix_task_agenda (tenant_id, assignee_user_id, due_at)} (PENDING/MISSED) e
 * {@code ix_dispute_open (tenant_id, creditor_id, response_due_date)} (OPEN/UNDER_REVIEW).
 */
@Component
class JdbcCollectionPendingWorkQueries implements CollectionPendingWorkQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCollectionPendingWorkQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PendingWorkCount tasksDue(Set<UUID> creditorScope, UUID userId, Instant now, Instant startOfTomorrow,
                                     Instant missedSince) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return PendingWorkCount.NONE;
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("me", userId)
                .addValue("now", Timestamp.from(now)).addValue("tomorrow", Timestamp.from(startOfTomorrow))
                .addValue("since", Timestamp.from(missedSince));
        StringBuilder sql = new StringBuilder("""
                select count(*) as total,
                       count(*) filter (where status = 'MISSED' or due_at < :now) as overdue,
                       count(*) filter (where status = 'PENDING' and due_at >= :now) as due_today,
                       min(due_at) as oldest_at, min(due_at) as next_due,
                       case when count(*) = 1 then (array_agg(case_id))[1] end as single_id
                  from collection.task
                 where assignee_user_id = :me and status in ('PENDING', 'MISSED') and due_at < :tomorrow
                   and (status = 'PENDING' or due_at >= :since)
                """);
        if (creditorScope != null) {
            sql.append(" and creditor_id in (:scope)");
            params.addValue("scope", creditorScope);
        }
        return jdbc.queryForObject(sql.toString(), params, PendingWorkRows.COUNT);
    }

    @Override
    public PendingWorkCount disputesDue(Set<UUID> creditorScope, LocalDate today, LocalDate until) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return PendingWorkCount.NONE;
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("today", Date.valueOf(today))
                .addValue("until", Date.valueOf(until));
        StringBuilder sql = new StringBuilder("""
                select count(*) as total,
                       count(*) filter (where response_due_date < :today) as overdue,
                       count(*) filter (where response_due_date = :today) as due_today,
                       min(opened_at) as oldest_at, min(response_due_date) as next_due,
                       case when count(*) = 1 then (array_agg(id))[1] end as single_id
                  from collection.dispute
                 where status in ('OPEN', 'UNDER_REVIEW') and response_due_date <= :until
                """);
        if (creditorScope != null) {
            sql.append(" and creditor_id in (:scope)");
            params.addValue("scope", creditorScope);
        }
        return jdbc.queryForObject(sql.toString(), params, PendingWorkRows.COUNT);
    }
}
