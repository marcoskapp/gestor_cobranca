package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.application.port.out.CompliancePendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRows;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/** Índice: {@code ix_dsr_status_due (tenant_id, status, due_at)}. */
@Component
class JdbcCompliancePendingWorkQueries implements CompliancePendingWorkQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCompliancePendingWorkQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PendingWorkCount dataSubjectRequestsDueSoon(Instant now, Instant startOfTomorrow, Instant dueBefore) {
        return jdbc.queryForObject("""
                select count(*) as total,
                       count(*) filter (where due_at < :now) as overdue,
                       count(*) filter (where due_at >= :now and due_at < :tomorrow) as due_today,
                       min(received_at) as oldest_at, min(due_at) as next_due,
                       case when count(*) = 1 then (array_agg(id))[1] end as single_id
                  from compliance.data_subject_request
                 where status in ('RECEIVED', 'IDENTITY_VERIFIED', 'IN_PROGRESS') and due_at < :until
                """, new MapSqlParameterSource().addValue("now", Timestamp.from(now))
                .addValue("tomorrow", Timestamp.from(startOfTomorrow)).addValue("until", Timestamp.from(dueBefore)),
                PendingWorkRows.COUNT);
    }
}
