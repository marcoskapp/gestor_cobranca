package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.application.port.out.IncentivePendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRows;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Índices parciais de V1890 ({@code ix_commission_statement_pending}, {@code ix_statement_dispute_open}, {@code ix_statement_adjustment_pending}). */
@Component
class JdbcIncentivePendingWorkQueries implements IncentivePendingWorkQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcIncentivePendingWorkQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PendingWorkCount statementsAwaitingApproval(UUID approverId) {
        return jdbc.queryForObject("""
                select count(*) as total, 0 as overdue, 0 as due_today, min(s.calculated_at) as oldest_at,
                       null::date as next_due, case when count(*) = 1 then (array_agg(s.id))[1] end as single_id
                  from incentive.commission_statement s
                  join incentive.commission_period p on p.id = s.period_id
                 where s.status in ('CALCULATED', 'ADJUSTED') and s.beneficiary_id <> :me
                   and (p.closed_by is null or p.closed_by <> :me)
                   and not exists (select 1 from incentive.statement_adjustment a
                                    where a.statement_id = s.id
                                      and (a.status = 'PENDING_SECOND_APPROVAL' or a.requested_by = :me
                                           or a.decided_by = :me))
                   and not exists (select 1 from incentive.statement_dispute d
                                    where d.statement_id = s.id and (d.status = 'OPEN' or d.resolved_by = :me))
                """, new MapSqlParameterSource("me", approverId), PendingWorkRows.COUNT);
    }

    @Override
    public PendingWorkCount openDisputes(UUID userId) {
        return jdbc.queryForObject("""
                select count(*) as total, 0 as overdue, 0 as due_today, min(d.opened_at) as oldest_at,
                       null::date as next_due, case when count(*) = 1 then (array_agg(d.statement_id))[1] end as single_id
                  from incentive.statement_dispute d
                 where d.status = 'OPEN' and d.opened_by <> :me
                """, new MapSqlParameterSource("me", userId), PendingWorkRows.COUNT);
    }

    @Override
    public PendingWorkCount adjustmentsAwaitingApproval(UUID userId) {
        return jdbc.queryForObject("""
                select count(*) as total, 0 as overdue, 0 as due_today, min(a.requested_at) as oldest_at,
                       null::date as next_due, case when count(*) = 1 then (array_agg(a.statement_id))[1] end as single_id
                  from incentive.statement_adjustment a
                  join incentive.commission_statement s on s.id = a.statement_id
                 where a.status = 'PENDING_SECOND_APPROVAL' and a.requested_by <> :me and s.beneficiary_id <> :me
                   and s.status not in ('APPROVED', 'EXPORTED')
                """, new MapSqlParameterSource("me", userId), PendingWorkRows.COUNT);
    }
}
