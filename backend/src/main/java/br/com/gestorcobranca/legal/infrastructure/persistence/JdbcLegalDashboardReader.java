package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.application.port.out.LegalDashboardReader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
class JdbcLegalDashboardReader implements LegalDashboardReader {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcLegalDashboardReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StageRow> stages(Set<UUID> creditorScope, UUID lawFirmId, UUID creditorId) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                select stage, count(*) as matters, coalesce(sum(debt_amount), 0) as debt,
                       coalesce(sum(expenses_total), 0) as expenses, coalesce(sum(recovered_amount), 0) as recovered
                  from legal.legal_matter where 1 = 1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (creditorScope != null) {
            sql.append(" and creditor_id in (:scope)");
            params.addValue("scope", creditorScope);
        }
        if (lawFirmId != null) {
            sql.append(" and law_firm_id = :firm");
            params.addValue("firm", lawFirmId);
        }
        if (creditorId != null) {
            sql.append(" and creditor_id = :creditor");
            params.addValue("creditor", creditorId);
        }
        sql.append(" group by stage order by stage");
        return jdbc.query(sql.toString(), params, (rs, i) -> new StageRow(rs.getString("stage"), rs.getLong("matters"),
                rs.getBigDecimal("debt"), rs.getBigDecimal("expenses"), rs.getBigDecimal("recovered")));
    }
}
