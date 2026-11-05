package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.application.port.out.DebtPendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRows;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/** Índices parciais {@code ix_amount_adjustment_pending} e {@code ix_amount_divergence_open} (V0304). */
@Component
class JdbcDebtPendingWorkQueries implements DebtPendingWorkQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcDebtPendingWorkQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PendingWorkCount amountApprovals(Set<UUID> creditorScope, UUID approverId) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return PendingWorkCount.NONE;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("me", approverId);
        String sql = """
                select count(*) as total, 0 as overdue, 0 as due_today, min(requested_at) as oldest_at,
                       null::date as next_due, case when count(*) = 1 then (array_agg(title_id))[1] end as single_id
                  from debt.title_amount_adjustment
                 where status = 'PENDING_APPROVAL' and requested_by <> :me
                """ + scope(creditorScope, params);
        return jdbc.queryForObject(sql, params, PendingWorkRows.COUNT);
    }

    @Override
    public PendingWorkCount amountDivergences(Set<UUID> creditorScope) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return PendingWorkCount.NONE;
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = """
                select count(*) as total, 0 as overdue, 0 as due_today, min(detected_at) as oldest_at,
                       null::date as next_due, case when count(*) = 1 then (array_agg(title_id))[1] end as single_id
                  from debt.title_amount_divergence
                 where status = 'OPEN'
                """ + scope(creditorScope, params);
        return jdbc.queryForObject(sql, params, PendingWorkRows.COUNT);
    }

    private static String scope(Set<UUID> creditorScope, MapSqlParameterSource params) {
        if (creditorScope == null) {
            return "";
        }
        params.addValue("scope", creditorScope);
        return " and creditor_id in (:scope)";
    }
}
