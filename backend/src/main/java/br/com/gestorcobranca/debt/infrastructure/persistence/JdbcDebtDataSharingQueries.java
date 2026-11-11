package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.application.port.out.DebtDataSharingQueries;
import br.com.gestorcobranca.shared.privacy.DataSharingRows;
import br.com.gestorcobranca.shared.privacy.DataSharingUsage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Índices: {@code ix_debt_title_debtor (tenant_id, debtor_id)} e {@code ix_title_ownership_title (tenant_id, title_id,
 * assigned_at)}; credor pela chave primária.
 */
@Component
class JdbcDebtDataSharingQueries implements DebtDataSharingQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcDebtDataSharingQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DataSharingUsage> creditorsOf(UUID debtorId, Set<UUID> creditorScope) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("debtor", debtorId);
        StringBuilder sql = new StringBuilder("""
                with owners as (
                    select t.creditor_id, t.created_at as at, t.id as title_id
                      from debt.debt_title t
                     where t.debtor_id = :debtor
                    union all
                    select o.creditor_id, o.assigned_at, o.title_id
                      from debt.title_ownership o
                      join debt.debt_title t on t.id = o.title_id
                     where t.debtor_id = :debtor
                )
                select null as code, o.creditor_id as recipient_id, c.trade_name as name, min(o.at) as first_at,
                       max(o.at) as last_at, count(distinct o.title_id) as total, null::uuid as latest_id
                  from owners o
                  join organization.creditor c on c.id = o.creditor_id
                """);
        if (creditorScope != null) {
            sql.append(" where o.creditor_id in (:scope)");
            params.addValue("scope", creditorScope);
        }
        sql.append(" group by o.creditor_id, c.trade_name order by min(o.at)");
        return jdbc.query(sql.toString(), params, DataSharingRows.USAGE);
    }
}
