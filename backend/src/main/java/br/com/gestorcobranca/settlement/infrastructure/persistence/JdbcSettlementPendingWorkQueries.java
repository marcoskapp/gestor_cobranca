package br.com.gestorcobranca.settlement.infrastructure.persistence;

import br.com.gestorcobranca.settlement.application.port.out.SettlementPendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRows;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Parte dos demonstrativos em aberto ({@code uk_statement_open}: no máximo um por credor) e chega aos
 * ajustes por {@code ix_statement_adjustment_statement (tenant_id, statement_id, status)}.
 */
@Component
class JdbcSettlementPendingWorkQueries implements SettlementPendingWorkQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcSettlementPendingWorkQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PendingWorkCount awaitingReview(Set<UUID> creditorScope) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        return query("""
                select count(*) as total, 0 as overdue, 0 as due_today, min(s.calculated_at) as oldest_at,
                       null::date as next_due, case when count(*) = 1 then (array_agg(s.id))[1] end as single_id
                  from settlement.statement s
                 where s.status = 'CALCULATED'
                """, creditorScope, params);
    }

    @Override
    public PendingWorkCount awaitingApproval(Set<UUID> creditorScope, UUID approverId) {
        MapSqlParameterSource params = new MapSqlParameterSource("me", approverId);
        return query("""
                select count(*) as total, 0 as overdue, 0 as due_today, min(s.reviewed_at) as oldest_at,
                       null::date as next_due, case when count(*) = 1 then (array_agg(s.id))[1] end as single_id
                  from settlement.statement s
                 where s.status = 'REVIEWED' and (s.calculated_by is null or s.calculated_by <> :me)
                   and not exists (select 1 from settlement.statement_adjustment a
                                    where a.statement_id = s.id
                                      and (a.status = 'PENDING_SECOND_APPROVAL'
                                           or (a.status = 'APPLIED' and a.requested_by = :me)))
                """, creditorScope, params);
    }

    @Override
    public PendingWorkCount adjustmentsAwaitingApproval(Set<UUID> creditorScope, UUID approverId) {
        MapSqlParameterSource params = new MapSqlParameterSource("me", approverId);
        return query("""
                select count(*) as total, 0 as overdue, 0 as due_today, min(a.requested_at) as oldest_at,
                       null::date as next_due, case when count(*) = 1 then (array_agg(s.id))[1] end as single_id
                  from settlement.statement s
                  join settlement.statement_adjustment a on a.statement_id = s.id
                 where s.status in ('CALCULATED', 'REVIEWED') and a.status = 'PENDING_SECOND_APPROVAL'
                   and a.requested_by <> :me
                """, creditorScope, params);
    }

    private PendingWorkCount query(String sql, Set<UUID> creditorScope, MapSqlParameterSource params) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return PendingWorkCount.NONE;
        }
        String scoped = sql;
        if (creditorScope != null) {
            scoped = sql + " and s.creditor_id in (:scope)";
            params.addValue("scope", creditorScope);
        }
        return jdbc.queryForObject(scoped, params, PendingWorkRows.COUNT);
    }
}
