package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.application.port.out.IngestionPendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRows;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Índices: {@code ix_import_review_status (tenant_id, status, created_at)} e
 * {@code ix_import_review_alert_batch (tenant_id, batch_id, status, …)}.
 */
@Component
class JdbcIngestionPendingWorkQueries implements IngestionPendingWorkQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcIngestionPendingWorkQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PendingWorkCount awaitingApproval(Set<UUID> creditorScope, UUID approverId) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return PendingWorkCount.NONE;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("me", approverId);
        String sql = """
                select count(*) as total, 0 as overdue, 0 as due_today, min(r.commit_requested_at) as oldest_at,
                       null::date as next_due, case when count(*) = 1 then (array_agg(r.id))[1] end as single_id
                  from ingestion.import_review r
                 where r.status = 'AWAITING_APPROVAL'
                   and (r.commit_requested_by is null or r.commit_requested_by <> :me)
                   and (r.created_by is null or r.created_by <> :me)
                """ + scope(creditorScope, params);
        return jdbc.queryForObject(sql, params, PendingWorkRows.COUNT);
    }

    @Override
    public PendingWorkCount openAlerts(Set<UUID> creditorScope, UUID ownerId) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return PendingWorkCount.NONE;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("me", ownerId);
        String sql = """
                select count(*) as total, 0 as overdue, 0 as due_today,
                       min(coalesce(r.analysis_finished_at, r.created_at)) as oldest_at, null::date as next_due,
                       case when count(*) = 1 then (array_agg(r.id))[1] end as single_id
                  from ingestion.import_review r
                 where r.status = 'OPEN' and r.created_by = :me and r.analysis_status = 'DONE'
                   and exists (select 1 from ingestion.import_review_alert a
                                where a.batch_id = r.analysis_batch_id and a.status = 'OPEN')
                """ + scope(creditorScope, params);
        return jdbc.queryForObject(sql, params, PendingWorkRows.COUNT);
    }

    private static String scope(Set<UUID> creditorScope, MapSqlParameterSource params) {
        if (creditorScope == null) {
            return "";
        }
        params.addValue("scope", creditorScope);
        return " and r.creditor_id in (:scope)";
    }
}
