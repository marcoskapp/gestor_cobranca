package br.com.gestorcobranca.assistant.infrastructure.persistence;

import br.com.gestorcobranca.assistant.application.port.out.QualityStatistics;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Agregados das métricas de qualidade (sob RLS; escopo de credores no filtro). */
@Component
class JdbcQualityStatistics implements QualityStatistics {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcQualityStatistics(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Snapshot snapshot(Instant from, Instant to, Set<UUID> creditorIds, UUID creditorId, UUID portfolioId,
                             int earlyBreakDays) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to))
                .addValue("restricted", creditorIds != null)
                .addValue("creditors", creditorIds == null || creditorIds.isEmpty() ? Set.of(new UUID(0, 0)) : creditorIds)
                .addValue("creditor", creditorId).addValue("portfolio", portfolioId)
                .addValue("earlyDays", earlyBreakDays);
        String scope = """
                (not :restricted or creditor_id in (:creditors))
                and (cast(:creditor as uuid) is null or creditor_id = :creditor)
                and (cast(:portfolio as uuid) is null or portfolio_id = :portfolio)
                """;
        String turnsWhere = " where started_at >= :from and started_at < :to and " + scope;
        Map<String, Object> totals = jdbc.queryForMap("""
                select count(*) as turns, count(distinct conversation_id) as conversations,
                       coalesce(sum(cost_amount), 0) as cost, max(cost_currency) as currency,
                       coalesce(sum(input_tokens), 0) as input_tokens, coalesce(sum(output_tokens), 0) as output_tokens,
                       coalesce(round(avg(total_latency_ms)), 0) as latency
                  from assistant.assistant_turn""" + turnsWhere, p);
        Map<String, Long> outcomes = grouped("select outcome as k, count(*) as n from assistant.assistant_turn"
                + turnsWhere + " group by outcome", p);
        Map<String, Long> handoffs = grouped("select handoff_reason as k, count(*) as n from assistant.assistant_turn"
                + turnsWhere + " and handoff_reason is not null group by handoff_reason", p);
        Map<String, Long> guardrails = grouped("""
                select g.code as k, count(*) as n from assistant.assistant_turn t,
                       jsonb_array_elements_text(t.guardrails) as g(code)""" + turnsWhere + " group by g.code", p);
        String factsWhere = " where formalized_at >= :from and formalized_at < :to and " + scope;
        Map<String, Long> agreements = grouped("select coalesce(origin, 'UNKNOWN') as k, count(*) as n "
                + "from assistant.agreement_fact" + factsWhere + " group by 1", p);
        Map<String, Long> early = grouped("select coalesce(origin, 'UNKNOWN') as k, count(*) as n "
                + "from assistant.agreement_fact" + factsWhere
                + " and broken_at is not null and broken_at < formalized_at + make_interval(days => :earlyDays) group by 1", p);
        String reviewsWhere = " where created_at >= :from and created_at < :to and " + scope;
        Map<String, Object> review = jdbc.queryForMap("""
                select count(*) filter (where status = 'REVIEWED') as reviewed,
                       avg(score) filter (where status = 'REVIEWED') as avg_score,
                       count(*) filter (where verdict = 'OUT_OF_POLICY') as out_of_policy,
                       count(*) filter (where status = 'PENDING') as pending
                  from assistant.turn_review""" + reviewsWhere, p);
        String pixWhere = " where requested_at >= :from and requested_at < :to and " + scope;
        Map<String, Object> pix = jdbc.queryForMap("""
                select count(*) filter (where decision = 'ISSUED' and delivery_status <> 'CANCELLED') as issued,
                       count(*) filter (where delivery_status = 'DELIVERED') as delivered,
                       count(*) filter (where decision = 'SUGGESTED') as suggested,
                       count(*) filter (where decision = 'ISSUED' and paid_at is not null) as paid,
                       count(*) filter (where decision = 'ISSUED' and paid_at is not null
                                          and paid_at <= requested_at + interval '24 hours') as paid24
                  from assistant.pix_request""" + pixWhere, p);
        Map<String, Long> denied = grouped("select denial_code as k, count(*) as n from assistant.pix_request" + pixWhere
                + " and decision = 'DENIED' group by denial_code", p);
        QualityStatistics.PixStats pixStats = new QualityStatistics.PixStats(num(pix.get("issued")),
                num(pix.get("delivered")), denied, num(pix.get("suggested")), num(pix.get("paid")),
                num(pix.get("paid24")));
        BigDecimal avgScore = review.get("avg_score") == null ? null
                : new BigDecimal(review.get("avg_score").toString()).setScale(2, java.math.RoundingMode.HALF_EVEN);
        return new Snapshot(num(totals.get("turns")), num(totals.get("conversations")), outcomes, handoffs, guardrails,
                new BigDecimal(totals.get("cost").toString()), (String) totals.get("currency"),
                num(totals.get("input_tokens")), num(totals.get("output_tokens")), num(totals.get("latency")), agreements,
                early, num(review.get("reviewed")), avgScore, num(review.get("out_of_policy")), num(review.get("pending")),
                pixStats);
    }

    private Map<String, Long> grouped(String sql, MapSqlParameterSource p) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query(sql + " order by 2 desc, 1", p, rs -> {
            out.put(rs.getString("k"), rs.getLong("n"));
        });
        return out;
    }

    private static long num(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }
}
