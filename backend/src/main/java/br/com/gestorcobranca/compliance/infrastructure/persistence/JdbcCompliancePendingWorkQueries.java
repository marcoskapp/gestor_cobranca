package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.application.port.out.CompliancePendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRows;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Índices: {@code ix_dsr_status_due (tenant_id, status, due_at)};
 * {@code ix_vuln_suggestion_status (tenant_id, status, creditor_id, suggested_at)}.
 */
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

    @Override
    public PendingWorkCount vulnerabilitySuggestionsPending(Set<UUID> creditorScope) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String scope = "";
        if (creditorScope != null) {
            if (creditorScope.isEmpty()) {
                return PendingWorkCount.NONE;
            }
            // sugestão sem credor de origem só para quem tem todos os credores
            scope = " and creditor_id in (:scope)";
            params.addValue("scope", creditorScope);
        }
        return jdbc.queryForObject("""
                select count(*) as total, 0 as overdue, 0 as due_today,
                       min(suggested_at) as oldest_at, null::timestamptz as next_due,
                       case when count(*) = 1 then (array_agg(id))[1] end as single_id
                  from compliance.vulnerability_suggestion
                 where status = 'PENDING'
                """ + scope, params, PendingWorkRows.COUNT);
    }
}
