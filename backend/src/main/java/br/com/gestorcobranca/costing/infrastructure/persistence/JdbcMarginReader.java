package br.com.gestorcobranca.costing.infrastructure.persistence;

import br.com.gestorcobranca.costing.application.port.out.MarginReader;
import br.com.gestorcobranca.costing.domain.model.ChannelItem;
import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.ItemEstimate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Somas dos fatos apurados por credor × carteira × mês, com o recorte de escopo já resolvido pelo serviço. */
@Repository
class JdbcMarginReader implements MarginReader {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcMarginReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static MapSqlParameterSource params(Slice s) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("from", s.from(), Types.DATE)
                .addValue("to", s.to(), Types.DATE);
        if (s.creditorIds() != null) {
            p.addValue("creditors", s.creditorIds());
        }
        if (!s.portfolioIds().isEmpty()) {
            p.addValue("portfolios", s.portfolioIds());
        }
        return p;
    }

    /** Filtro do recorte (lista fixa de trechos: nada vem do cliente além dos parâmetros). */
    private static String where(Slice s) {
        StringBuilder sql = new StringBuilder(" where day between :from and :to");
        if (s.creditorIds() != null) {
            sql.append(" and creditor_id in (:creditors)");
        } else if (!s.includeUnallocated()) {
            sql.append(" and creditor_id is not null");
        }
        if (!s.portfolioIds().isEmpty()) {
            sql.append(" and portfolio_id in (:portfolios)");
        }
        return sql.toString();
    }

    @Override
    public List<CostRow> costs(Slice slice) {
        return costRows(slice, "ESTIMATED");
    }

    @Override
    public List<CostRow> invoiceAdjustments(Slice slice) {
        return costRows(slice, "INVOICE_ADJUSTMENT");
    }

    /** Somas de uma base dos fatos (lista fixa: {@code ESTIMATED} ou {@code INVOICE_ADJUSTMENT}). */
    private List<CostRow> costRows(Slice slice, String basis) {
        return jdbc.query("select creditor_id, portfolio_id, to_char(day, 'YYYY-MM') as month, category, "
                        + "sum(amount) as amount, sum(quantity) as quantity from costing.cost_daily" + where(slice)
                        + " and basis = :basis group by 1, 2, 3, 4", params(slice).addValue("basis", basis),
                (rs, i) -> new CostRow(JdbcUsageReader.uuid(rs, "creditor_id"), JdbcUsageReader.uuid(rs, "portfolio_id"),
                        YearMonth.parse(rs.getString("month")), CostCategory.valueOf(rs.getString("category")),
                        rs.getBigDecimal("amount"), rs.getBigDecimal("quantity")));
    }

    @Override
    public List<ItemMonthRow> channelEstimates(LocalDate from, LocalDate to) {
        return jdbc.query("""
                        select to_char(day, 'YYYY-MM') as month, item, sum(amount) as amount, sum(quantity) as quantity
                          from costing.cost_daily
                         where day between :from and :to and basis = 'ESTIMATED' and item is not null
                         group by 1, 2
                        """, new MapSqlParameterSource().addValue("from", from, Types.DATE).addValue("to", to, Types.DATE),
                (rs, i) -> new ItemMonthRow(YearMonth.parse(rs.getString("month")),
                        ChannelItem.valueOf(rs.getString("item")),
                        new ItemEstimate(rs.getBigDecimal("amount"), rs.getBigDecimal("quantity"))));
    }

    @Override
    public List<ActivityRow> activity(Slice slice) {
        return jdbc.query("select creditor_id, portfolio_id, to_char(day, 'YYYY-MM') as month, "
                        + "sum(recovered) as recovered, sum(revenue) as revenue, sum(settled_agreements) as settled "
                        + "from costing.activity_daily" + where(slice) + " group by 1, 2, 3", params(slice),
                (rs, i) -> new ActivityRow(JdbcUsageReader.uuid(rs, "creditor_id"),
                        JdbcUsageReader.uuid(rs, "portfolio_id"), YearMonth.parse(rs.getString("month")),
                        rs.getBigDecimal("recovered"), rs.getBigDecimal("revenue"), rs.getLong("settled")));
    }

    @Override
    public List<WarningRow> warnings(Slice slice) {
        return jdbc.query("select code, sum(quantity) as quantity, count(distinct date_trunc('month', day)) as months "
                        + "from costing.cost_warning_daily" + where(slice) + " group by code order by code",
                params(slice), (rs, i) -> new WarningRow(rs.getString("code"), rs.getBigDecimal("quantity"),
                        rs.getLong("months")));
    }

    @Override
    public Map<UUID, String> creditorNames(Collection<UUID> creditorIds) {
        return names("select creditor_id as id, name from costing.src_creditor where creditor_id in (:ids)",
                creditorIds);
    }

    @Override
    public Map<UUID, String> portfolioNames(Collection<UUID> portfolioIds) {
        return names("select portfolio_id as id, name from costing.src_portfolio where portfolio_id in (:ids)",
                portfolioIds);
    }

    private Map<UUID, String> names(String sql, Collection<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        jdbc.query(sql, new MapSqlParameterSource("ids", ids), rs -> {
            names.put(JdbcUsageReader.uuid(rs, "id"), rs.getString("name"));
        });
        return names;
    }
}
