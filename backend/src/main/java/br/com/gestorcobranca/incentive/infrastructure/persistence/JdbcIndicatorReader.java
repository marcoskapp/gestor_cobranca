package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.application.port.out.IndicatorReader;
import br.com.gestorcobranca.shared.config.TimeConfig;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Leituras agregadas dos fatos (SQL com parâmetros nomeados; dimensões de uma lista fixa). */
@Repository
class JdbcIndicatorReader implements IndicatorReader {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcIndicatorReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Totals> totals(Filter filter, Dimension groupBy) {
        MapSqlParameterSource params = params(filter);
        String where = factWhere(filter, true);
        Map<String, Totals> rows = new LinkedHashMap<>();
        jdbc.query("""
                select %s as group_key, sum(attempts), sum(effective_contacts), sum(answered_calls), sum(talk_seconds),
                       sum(agreements), sum(negotiated_amount), sum(agreement_amount), sum(updated_balance),
                       sum(discount_amount), sum(down_payment_amount), sum(envelope_usage_sum), sum(envelope_count),
                       sum(concession_level_sum), sum(concession_count), sum(first_installments), sum(accrued_amount),
                       sum(reversed_amount), sum(callbacks_scheduled), sum(callbacks_kept), sum(callbacks_missed),
                       sum(promises_registered), sum(promises_due), sum(promises_kept), sum(promises_broken)
                from incentive.performance_fact f
                where %s
                group by 1
                """.formatted(factGroup(groupBy), where), params, rs -> {
            String key = rs.getString(1);
            rows.put(key, new Totals(key, rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6),
                    rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10),
                    rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getLong(13), rs.getLong(14), rs.getLong(15),
                    rs.getLong(16), rs.getBigDecimal(17), rs.getBigDecimal(18), null, null,
                    new Commitments(rs.getLong(19), rs.getLong(20), rs.getLong(21), rs.getLong(22), rs.getLong(23),
                            rs.getLong(24), rs.getLong(25))));
        });
        boolean noBucket = groupBy != Dimension.DELAY_BUCKET
                && (filter.delayBuckets() == null || filter.delayBuckets().isEmpty());
        if (noBucket) {
            Map<String, Long> worked = new HashMap<>();
            jdbc.query("""
                    select %s as group_key, count(distinct f.case_id) from incentive.worked_case_day f where %s group by 1
                    """.formatted(factGroup(groupBy), factWhere(filter, false)), params,
                    rs -> {
                        worked.put(rs.getString(1), rs.getLong(2));
                    });
            Map<String, BigDecimal> median = new HashMap<>();
            jdbc.query("""
                    select %s as group_key, percentile_cont(0.5) within group (order by f.response_seconds)
                    from incentive.response_sample f where %s group by 1
                    """.formatted(factGroup(groupBy), factWhere(filter, false)), params,
                    rs -> {
                        median.put(rs.getString(1), rs.getBigDecimal(2).setScale(2, RoundingMode.HALF_EVEN));
                    });
            List<String> keys = new ArrayList<>(rows.keySet());
            worked.keySet().stream().filter(k -> !rows.containsKey(k)).forEach(keys::add);
            List<Totals> result = new ArrayList<>();
            for (String key : keys) {
                Totals t = rows.getOrDefault(key, empty(key));
                result.add(new Totals(key, t.attempts(), t.effectiveContacts(), t.answeredCalls(), t.talkSeconds(),
                        t.agreements(), t.negotiatedAmount(), t.agreementAmount(), t.updatedBalance(), t.discountAmount(),
                        t.downPaymentAmount(), t.envelopeUsageSum(), t.envelopeCount(), t.concessionLevelSum(),
                        t.concessionCount(), t.firstInstallments(), t.accruedAmount(), t.reversedAmount(),
                        worked.getOrDefault(key, 0L), median.get(key), t.commitments()));
            }
            return result;
        }
        return new ArrayList<>(rows.values());
    }

    private static Totals empty(String key) {
        return new Totals(key, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, null, null,
                Commitments.NONE);
    }

    @Override
    public List<LoggedTime> loggedTime(LocalDate from, LocalDate to, Collection<UUID> userIds) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("from", from, Types.DATE)
                .addValue("to", to, Types.DATE);
        String users = "";
        if (userIds != null) {
            if (userIds.isEmpty()) {
                return List.of();
            }
            users = " and user_id in (:users)";
            params.addValue("users", userIds);
        }
        return jdbc.query("""
                select user_id, sum(seconds) from incentive.logged_time_daily
                where fact_date between :from and :to%s group by user_id
                """.formatted(users), params, (rs, i) -> new LoggedTime(rs.getObject(1, UUID.class), rs.getLong(2)));
    }

    @Override
    public List<SafraTotals> safra(Filter filter, Dimension groupBy) {
        MapSqlParameterSource params = params(filter)
                .addValue("fromTs", JdbcFactStore.ts(start(filter.from())), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("toTs", JdbcFactStore.ts(start(filter.to().plusDays(1))), Types.TIMESTAMP_WITH_TIMEZONE);
        StringBuilder where = new StringBuilder("a.formalized_at >= :fromTs and a.formalized_at < :toTs");
        if (filter.userIds() != null) {
            where.append(filter.userIds().isEmpty() ? " and false" : " and p.actor_id in (:users)");
        }
        if (!isEmpty(filter.creditorIds())) {
            where.append(" and a.creditor_id in (:creditors)");
        }
        if (!isEmpty(filter.portfolioIds())) {
            where.append(" and a.portfolio_id in (:portfolios)");
        }
        if (!isEmpty(filter.delayBuckets())) {
            where.append(" and a.delay_bucket in (:buckets)");
        }
        if (!isEmpty(filter.channels())) {
            where.append(" and a.channel in (:channels)");
        }
        String group = switch (groupBy) {
            case USER -> "p.actor_id::text";
            case CREDITOR -> "a.creditor_id::text";
            case PORTFOLIO -> "coalesce(a.portfolio_id::text, '-')";
            case DELAY_BUCKET -> "a.delay_bucket";
            case CHANNEL -> "a.channel";
            case NONE -> "'ALL'";
        };
        return jdbc.query("""
                select %s as group_key, count(*),
                       count(*) filter (where l.broken_at is not null and l.broken_at <= a.formalized_at + interval '30 days'),
                       count(*) filter (where l.broken_at is not null and l.broken_at <= a.formalized_at + interval '60 days'),
                       count(*) filter (where l.broken_at is not null and l.broken_at <= a.formalized_at + interval '90 days'),
                       count(*) filter (where l.first_installment_paid_at is not null),
                       count(*) filter (where l.status = 'RENEGOTIATED' or l.renegotiated_at is not null)
                from incentive.agreement_attribution a
                join incentive.attribution_participant p on p.attribution_id = a.id and p.role = 'CLOSER'
                     and p.actor_type = 'USER'
                left join incentive.agreement_lifecycle l on l.tenant_id = a.tenant_id and l.agreement_id = a.agreement_id
                where %s
                group by 1
                """.formatted(group, where), params, (rs, i) -> new SafraTotals(rs.getString(1), rs.getLong(2),
                rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)));
    }

    @Override
    public List<QualityTotals> quality(LocalDate from, LocalDate to, Collection<UUID> userIds) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("from", from, Types.DATE)
                .addValue("to", to, Types.DATE);
        String users = "";
        if (userIds != null) {
            if (userIds.isEmpty()) {
                return List.of();
            }
            users = " and %s in (:users)";
            params.addValue("users", userIds);
        }
        Map<UUID, long[]> occurrences = new HashMap<>();
        jdbc.query("""
                select user_id,
                       count(*) filter (where type = 'COMPLAINT'),
                       count(*) filter (where type = 'UPHELD_DISPUTE'),
                       count(*) filter (where type in ('COMPLIANCE_VIOLATION', 'SEVERE_COMPLIANCE_VIOLATION')),
                       count(*) filter (where type = 'SEVERE_COMPLIANCE_VIOLATION')
                from incentive.conduct_occurrence
                where status = 'CONFIRMED' and occurred_on between :from and :to%s
                group by user_id
                """.formatted(users.formatted("user_id")), params, rs -> {
            occurrences.put(rs.getObject(1, UUID.class), new long[]{rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)});
        });
        Map<UUID, Object[]> evaluations = new HashMap<>();
        jdbc.query("""
                select evaluated_user_id, count(*), avg(score)
                from incentive.quality_evaluation
                where evaluated_on between :from and :to%s
                group by evaluated_user_id
                """.formatted(users.formatted("evaluated_user_id")), params, rs -> {
            evaluations.put(rs.getObject(1, UUID.class), new Object[]{rs.getLong(2),
                    rs.getBigDecimal(3).setScale(2, RoundingMode.HALF_EVEN)});
        });
        List<QualityTotals> result = new ArrayList<>();
        java.util.Set<UUID> all = new java.util.LinkedHashSet<>(occurrences.keySet());
        all.addAll(evaluations.keySet());
        for (UUID user : all) {
            long[] o = occurrences.getOrDefault(user, new long[4]);
            Object[] e = evaluations.get(user);
            result.add(new QualityTotals(user, o[0], o[1], o[2], o[3], e == null ? 0 : (Long) e[0],
                    e == null ? null : (BigDecimal) e[1]));
        }
        return result;
    }

    @Override
    public BigDecimal eligibleBalanceAt(Collection<UUID> userIds, Instant at, Collection<UUID> creditorIds) {
        return JdbcFactStore.eligibleBalance(jdbc, userIds, at, creditorIds);
    }

    // ------------------------------------------------------------------ SQL auxiliar

    private static String factGroup(Dimension dimension) {
        return switch (dimension) {
            case USER -> "f.user_id::text";
            case CREDITOR -> "coalesce(f.creditor_id::text, '-')";
            case PORTFOLIO -> "coalesce(f.portfolio_id::text, '-')";
            case DELAY_BUCKET -> "f.delay_bucket";
            case CHANNEL -> "f.channel";
            case NONE -> "'ALL'";
        };
    }

    private static String factWhere(Filter filter, boolean hasBucket) {
        StringBuilder where = new StringBuilder("f.fact_date between :from and :to");
        if (filter.userIds() != null) {
            where.append(filter.userIds().isEmpty() ? " and false" : " and f.user_id in (:users)");
        }
        if (!isEmpty(filter.creditorIds())) {
            where.append(" and f.creditor_id in (:creditors)");
        }
        if (!isEmpty(filter.portfolioIds())) {
            where.append(" and f.portfolio_id in (:portfolios)");
        }
        if (hasBucket && !isEmpty(filter.delayBuckets())) {
            where.append(" and f.delay_bucket in (:buckets)");
        }
        if (!isEmpty(filter.channels())) {
            where.append(" and f.channel in (:channels)");
        }
        return where.toString();
    }

    private static MapSqlParameterSource params(Filter filter) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", filter.from(), Types.DATE)
                .addValue("to", filter.to(), Types.DATE);
        if (filter.userIds() != null && !filter.userIds().isEmpty()) {
            p.addValue("users", filter.userIds());
        }
        if (!isEmpty(filter.creditorIds())) {
            p.addValue("creditors", filter.creditorIds());
        }
        if (!isEmpty(filter.portfolioIds())) {
            p.addValue("portfolios", filter.portfolioIds());
        }
        if (!isEmpty(filter.delayBuckets())) {
            p.addValue("buckets", filter.delayBuckets());
        }
        if (!isEmpty(filter.channels())) {
            p.addValue("channels", filter.channels());
        }
        return p;
    }

    private static Instant start(LocalDate date) {
        return date.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
    }

    private static boolean isEmpty(Collection<?> values) {
        return values == null || values.isEmpty();
    }
}
