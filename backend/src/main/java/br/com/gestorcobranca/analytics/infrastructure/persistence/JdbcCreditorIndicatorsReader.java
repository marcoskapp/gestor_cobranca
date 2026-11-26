package br.com.gestorcobranca.analytics.infrastructure.persistence;

import br.com.gestorcobranca.analytics.application.port.out.CreditorIndicatorsReader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Indicadores de um credor nos snapshots do analytics (portal do credor, fatia 7). Toda consulta de dado tem
 * {@code creditor_id = :creditorId} literal (sem o {@code SqlWhere} genérico dos painéis), só agrupa por mês ou carteira
 * e nunca lê operador, equipe, canal, origem, comissão ou custo. Índices: {@code ix_recovery_daily_creditor},
 * {@code ix_portfolio_position}, {@code ix_agreement_cohort_creditor_active}.
 */
@Repository
class JdbcCreditorIndicatorsReader implements CreditorIndicatorsReader {

    private static final String TOTALS = """
            coalesce(sum(r.recovered_gross), 0), coalesce(sum(r.reversed), 0),
            coalesce(sum(r.recovered_gross) filter (where r.received_by = 'CREDITOR'), 0),
            coalesce(sum(r.reversed) filter (where r.received_by = 'CREDITOR'), 0)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCreditorIndicatorsReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RecoveryTotals recovery(UUID creditorId, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                select %s from analytics.recovery_daily r
                where r.creditor_id = :creditorId and r.fact_date between :from and :to
                """.formatted(TOTALS), period(creditorId, from, to), (rs, i) -> totals(rs, 1));
    }

    @Override
    public List<MonthTotals> recoveryByMonth(UUID creditorId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select date_trunc('month', r.fact_date)::date as month, %s from analytics.recovery_daily r
                where r.creditor_id = :creditorId and r.fact_date between :from and :to
                group by 1 order by 1
                """.formatted(TOTALS), period(creditorId, from, to),
                (rs, i) -> new MonthTotals(rs.getObject(1, LocalDate.class), totals(rs, 2)));
    }

    @Override
    public List<PortfolioTotals> recoveryByPortfolio(UUID creditorId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select r.portfolio_id, coalesce(sum(r.recovered_gross), 0), coalesce(sum(r.reversed), 0)
                from analytics.recovery_daily r
                where r.creditor_id = :creditorId and r.fact_date between :from and :to
                group by 1
                """, period(creditorId, from, to),
                (rs, i) -> new PortfolioTotals(rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getBigDecimal(3)));
    }

    @Override
    public Map<UUID, String> portfolioNames(UUID creditorId, Collection<UUID> portfolioIds) {
        if (portfolioIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> result = new HashMap<>();
        jdbc.query("""
                select p.portfolio_id, p.code || ' — ' || p.name from analytics.src_portfolio p
                where p.creditor_id = :creditorId and p.portfolio_id in (:ids)
                """, new MapSqlParameterSource("creditorId", creditorId).addValue("ids", portfolioIds), rs -> {
            result.put(rs.getObject(1, UUID.class), rs.getString(2));
        });
        return result;
    }

    @Override
    public BalanceTotals balance(UUID creditorId) {
        return jdbc.queryForObject("""
                select coalesce(sum(p.portfolio_amount), 0), coalesce(sum(p.open_titles), 0), max(p.reference_date)
                from analytics.portfolio_position p where p.creditor_id = :creditorId
                """, new MapSqlParameterSource("creditorId", creditorId), (rs, i) -> new BalanceTotals(
                rs.getBigDecimal(1), rs.getLong(2), rs.getObject(3, LocalDate.class)));
    }

    @Override
    public List<AgreementStanding> activeAgreements(UUID creditorId) {
        return jdbc.query("""
                select c.overdue_installments > 0, c.break_suspended, count(*) from analytics.agreement_cohort c
                where c.creditor_id = :creditorId and c.status in ('FORMALIZED', 'IN_PAYMENT')
                group by 1, 2
                """, new MapSqlParameterSource("creditorId", creditorId),
                (rs, i) -> new AgreementStanding(rs.getBoolean(1), rs.getBoolean(2), rs.getLong(3)));
    }

    /** Carimbo da assessoria (não é dado do credor): o menor "dados até" dos conjuntos usados. */
    @Override
    public Instant dataUntil() {
        return instant(jdbc.queryForObject("""
                select case when count(*) = 3 then min(data_until) end from analytics.refresh_state
                where dataset in ('recovery_daily', 'agreement_cohort', 'portfolio_position')
                """, new MapSqlParameterSource(), OffsetDateTime.class));
    }

    @Override
    public Instant lastRefreshedAt() {
        return instant(jdbc.queryForObject("select max(refreshed_at) from analytics.refresh_state",
                new MapSqlParameterSource(), OffsetDateTime.class));
    }

    private static MapSqlParameterSource period(UUID creditorId, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource("creditorId", creditorId).addValue("from", from, Types.DATE)
                .addValue("to", to, Types.DATE);
    }

    private static RecoveryTotals totals(ResultSet rs, int first) throws SQLException {
        return new RecoveryTotals(rs.getBigDecimal(first), rs.getBigDecimal(first + 1), rs.getBigDecimal(first + 2),
                rs.getBigDecimal(first + 3));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
