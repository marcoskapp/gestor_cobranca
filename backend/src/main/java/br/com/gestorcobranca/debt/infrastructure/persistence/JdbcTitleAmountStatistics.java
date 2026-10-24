package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.application.port.in.TitleAmountStatisticsPort.Distribution;
import br.com.gestorcobranca.debt.application.port.out.TitleAmountStatisticsReader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mediana e MAD de {@code ln(principal)} por {@code percentile_cont} (a mediana de quantidade par é a média dos dois
 * centrais, igual ao cálculo em memória da ingestão). RLS restringe à assessoria da transação.
 */
@Repository
class JdbcTitleAmountStatistics implements TitleAmountStatisticsReader {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcTitleAmountStatistics(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Distribution distribution(UUID creditorId, UUID portfolioId) {
        MapSqlParameterSource p = new MapSqlParameterSource("creditor", creditorId)
                .addValue("portfolio", portfolioId, Types.OTHER);
        return jdbc.queryForObject("""
                with t as (
                    select ln(principal_amount::float8) as y, principal_amount = trunc(principal_amount) as integral
                    from debt.debt_title
                    where creditor_id = :creditor and principal_amount > 0
                      and (cast(:portfolio as uuid) is null or portfolio_id = cast(:portfolio as uuid))),
                m as (select percentile_cont(0.5) within group (order by y) as med from t)
                select count(*), coalesce((select med from m), 0),
                       coalesce(percentile_cont(0.5) within group (order by abs(t.y - (select med from m))), 0),
                       coalesce(avg(case when t.integral then 1.0 else 0.0 end), 0)
                from t
                """, p, (rs, i) -> new Distribution(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)));
    }

    @Override
    public Map<String, Distribution> byProduct(UUID creditorId, UUID portfolioId, Collection<String> products) {
        MapSqlParameterSource p = new MapSqlParameterSource("creditor", creditorId).addValue("portfolio", portfolioId)
                .addValue("products", products);
        Map<String, Distribution> result = new HashMap<>();
        jdbc.query("""
                with t as (
                    select c.product, ln(d.principal_amount::float8) as y,
                           d.principal_amount = trunc(d.principal_amount) as integral
                    from debt.debt_title d join debt.origin_contract c on c.id = d.origin_contract_id
                    where d.creditor_id = :creditor and d.portfolio_id = :portfolio and d.principal_amount > 0
                      and c.product in (:products)),
                m as (select product, percentile_cont(0.5) within group (order by y) as med from t group by product)
                select t.product, count(*), m.med, percentile_cont(0.5) within group (order by abs(t.y - m.med)),
                       avg(case when t.integral then 1.0 else 0.0 end)
                from t join m on m.product = t.product
                group by t.product, m.med
                """, p, rs -> {
            result.put(rs.getString(1), new Distribution(rs.getLong(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5)));
        });
        return result;
    }
}
