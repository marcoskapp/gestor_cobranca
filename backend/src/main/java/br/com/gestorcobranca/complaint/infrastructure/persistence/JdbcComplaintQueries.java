package br.com.gestorcobranca.complaint.infrastructure.persistence;

import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.CreditorIndicator;
import br.com.gestorcobranca.complaint.application.port.out.ComplaintQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRows;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Índices: {@code ix_complaint_open_due (tenant_id, creditor_id, warning_date) where status in (RECEIVED,
 * IN_PROGRESS)}, {@code ix_complaint_creditor_received (tenant_id, creditor_id, received_at)},
 * {@code ix_complaint_creditor_answered (tenant_id, creditor_id, answered_at)}, {@code ix_complaint_evidence_complaint}.
 */
@Component
class JdbcComplaintQueries implements ComplaintQueries {

    private static final String AWAITING = "status in ('RECEIVED', 'IN_PROGRESS')";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcComplaintQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PendingWorkCount dueSoon(Set<UUID> creditorScope, UUID assigneeId, boolean unassignedOnly, LocalDate today) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return PendingWorkCount.NONE;
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("today", Date.valueOf(today));
        StringBuilder sql = new StringBuilder("""
                select count(*) as total,
                       count(*) filter (where due_date < :today) as overdue,
                       count(*) filter (where due_date = :today) as due_today,
                       min(received_at) as oldest_at, min(due_date) as next_due,
                       case when count(*) = 1 then (array_agg(id))[1] end as single_id
                  from complaint.complaint
                 where\s""").append(AWAITING).append(" and warning_date <= :today");
        if (creditorScope != null) {
            sql.append(" and creditor_id in (:scope)");
            params.addValue("scope", creditorScope);
        }
        if (assigneeId != null) {
            sql.append(" and assignee_id = :assignee");
            params.addValue("assignee", assigneeId);
        }
        if (unassignedOnly) {
            sql.append(" and assignee_id is null");
        }
        return jdbc.queryForObject(sql.toString(), params, PendingWorkRows.COUNT);
    }

    @Override
    public List<CreditorIndicator> indicatorByCreditor(Set<UUID> creditorScope, Instant fromInclusive,
                                                       Instant toExclusive, LocalDate today) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(fromInclusive)).addValue("to", Timestamp.from(toExclusive))
                .addValue("today", Date.valueOf(today));
        String scope = "";
        if (creditorScope != null) {
            scope = " and creditor_id in (:scope)";
            params.addValue("scope", creditorScope);
        }
        return jdbc.query("""
                select creditor_id,
                       count(*) filter (where received_at >= :from and received_at < :to) as received,
                       count(*) filter (where answered_at >= :from and answered_at < :to and answered_on_time)
                           as answered_on_time,
                       count(*) filter (where answered_at >= :from and answered_at < :to and not answered_on_time)
                           as answered_late,
                       count(*) filter (where status in ('RECEIVED', 'IN_PROGRESS') and due_date < :today)
                           as open_overdue,
                       count(*) filter (where status in ('RECEIVED', 'IN_PROGRESS')) as open
                  from complaint.complaint
                 where ((received_at >= :from and received_at < :to)
                        or (answered_at >= :from and answered_at < :to)
                        or status in ('RECEIVED', 'IN_PROGRESS'))
                """ + scope + """
                 group by creditor_id
                 order by creditor_id
                """, params, (rs, i) -> new CreditorIndicator(rs.getObject("creditor_id", UUID.class),
                rs.getLong("received"), rs.getLong("answered_on_time"), rs.getLong("answered_late"),
                rs.getLong("open_overdue"), rs.getLong("open")));
    }

    @Override
    public Map<UUID, Long> openByAssignees(Collection<UUID> assigneeIds) {
        Map<UUID, Long> out = new HashMap<>();
        if (assigneeIds.isEmpty()) {
            return out;
        }
        jdbc.query("select assignee_id, count(*) as n from complaint.complaint where " + AWAITING
                        + " and assignee_id in (:ids) group by assignee_id",
                new MapSqlParameterSource("ids", assigneeIds),
                rs -> {
                    out.put(rs.getObject("assignee_id", UUID.class), rs.getLong("n"));
                });
        return out;
    }

    @Override
    public Map<UUID, Integer> evidenceCounts(Collection<UUID> complaintIds) {
        Map<UUID, Integer> out = new HashMap<>();
        if (complaintIds.isEmpty()) {
            return out;
        }
        jdbc.query("select complaint_id, count(*) as n from complaint.complaint_evidence where complaint_id in (:ids)"
                        + " and status in ('PENDING_SCAN', 'AVAILABLE') group by complaint_id",
                new MapSqlParameterSource("ids", complaintIds),
                rs -> {
                    out.put(rs.getObject("complaint_id", UUID.class), rs.getInt("n"));
                });
        return out;
    }
}
