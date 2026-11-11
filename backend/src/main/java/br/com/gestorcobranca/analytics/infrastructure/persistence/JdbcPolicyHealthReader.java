package br.com.gestorcobranca.analytics.infrastructure.persistence;

import br.com.gestorcobranca.analytics.application.port.out.PolicyHealthReader;
import br.com.gestorcobranca.analytics.domain.model.PolicySegment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agregados da saúde da política sobre a safra de acordos ({@code analytics.agreement_cohort}, atualizada pelo
 * agendamento) e as views de contrato {@code src_policy_agreement}, {@code src_portal_simulation} e
 * {@code src_portal_service_request} — todas com RLS de quem consulta.
 */
@Repository
class JdbcPolicyHealthReader implements PolicyHealthReader {

    /** Faixas de valor do acordo (limites em R$). */
    private static final Map<String, String> VALUE_BANDS = Map.of(
            "V0_1K", "c.total_amount < 1000",
            "V1K_5K", "c.total_amount >= 1000 and c.total_amount < 5000",
            "V5K_20K", "c.total_amount >= 5000 and c.total_amount < 20000",
            "V20K_PLUS", "c.total_amount >= 20000");

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPolicyHealthReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PolicySegment> agreements(UUID creditorId, LocalDate from, LocalDate to, String valueBand, Instant asOf) {
        String band = valueBand == null ? "true" : VALUE_BANDS.get(valueBand);
        if (band == null) {
            throw new IllegalArgumentException("faixa de valor desconhecida: " + valueBand);
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("creditor", creditorId)
                .addValue("from", from).addValue("to", to).addValue("asOf", Timestamp.from(asOf));
        return jdbc.query("""
                select c.portfolio_id, analytics.delay_bucket(p.max_days_overdue::integer) as bucket, count(*),
                       count(*) filter (where c.formalized_at + interval '60 days' <= :asOf),
                       count(*) filter (where c.formalized_at + interval '60 days' <= :asOf and c.broken_at is not null
                                        and c.broken_at <= c.formalized_at + interval '60 days'),
                       coalesce(sum(c.total_amount), 0), coalesce(sum(c.paid_net), 0),
                       coalesce(sum(p.envelope_usage), 0), count(p.envelope_usage),
                       count(*) filter (where p.envelope_usage >= 90)
                from analytics.agreement_cohort c
                join analytics.src_policy_agreement p on p.agreement_id = c.agreement_id
                where c.creditor_id = :creditor and c.formalized_on between :from and :to and %s
                group by 1, 2
                """.formatted(band), params, (rs, i) -> new PolicySegment(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                rs.getBigDecimal(8), rs.getLong(9), rs.getLong(10), 0, 0, 0));
    }

    @Override
    public List<PolicySegment> portal(UUID creditorId, Instant from, Instant to) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("creditor", creditorId)
                .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
        List<PolicySegment> out = new ArrayList<>(jdbc.query("""
                select s.portfolio_id, analytics.delay_bucket(s.days_overdue), count(*),
                       count(*) filter (where coalesce(s.offers, 0) = 0)
                from analytics.src_portal_simulation s
                where s.creditor_id = :creditor and s.occurred_at >= :from and s.occurred_at < :to
                group by 1, 2
                """, params, (rs, i) -> new PolicySegment(rs.getObject(1, UUID.class), rs.getString(2), 0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, rs.getLong(3), rs.getLong(4), 0)));
        // o pedido não tem faixa de atraso: entra na carteira, em "todas as faixas" (faixa nula)
        out.addAll(jdbc.query("""
                select r.portfolio_id, count(*)
                from analytics.src_portal_service_request r
                where r.creditor_id = :creditor and r.reason = 'CANNOT_AFFORD'
                  and r.created_at >= :from and r.created_at < :to
                group by 1
                """, params, (rs, i) -> new PolicySegment(rs.getObject(1, UUID.class), null, 0, 0, 0, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, rs.getLong(2))));
        return out;
    }
}
