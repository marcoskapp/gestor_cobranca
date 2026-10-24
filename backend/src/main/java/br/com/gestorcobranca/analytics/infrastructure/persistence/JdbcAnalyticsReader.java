package br.com.gestorcobranca.analytics.infrastructure.persistence;

import br.com.gestorcobranca.analytics.application.port.out.AnalyticsReader;
import br.com.gestorcobranca.analytics.application.port.out.Slice;
import br.com.gestorcobranca.analytics.domain.model.Dimension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Leituras do analytics: somente views {@code analytics.src_*} e snapshots {@code analytics.*},
 * sempre sob o RLS da assessoria da transação. Dimensões vêm de uma lista fixa (nunca do cliente).
 */
@Repository
class JdbcAnalyticsReader implements AnalyticsReader {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcAnalyticsReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ carteira

    @Override
    public LocalDate positionReferenceDate() {
        return jdbc.queryForObject("""
                select max(d) from (select max(reference_date) as d from analytics.portfolio_position
                                    union all select max(reference_date) from analytics.case_position) x
                """, new MapSqlParameterSource(), LocalDate.class);
    }

    @Override
    public List<PositionRow> titlePosition(Slice slice) {
        MapSqlParameterSource params = SqlWhere.params(slice);
        SqlWhere where = new SqlWhere(params).creditors("creditor_id", slice).in("portfolio_id", slice.portfolios());
        return jdbc.query("""
                select creditor_id, portfolio_id, delay_bucket, sum(open_titles), sum(portfolio_amount),
                       sum(overdue_titles), sum(overdue_amount)
                from analytics.portfolio_position where %s group by 1, 2, 3
                """.formatted(where), params, (rs, i) -> new PositionRow(uuid(rs, 1), uuid(rs, 2), rs.getString(3),
                rs.getLong(4), rs.getBigDecimal(5), rs.getLong(6), rs.getBigDecimal(7)));
    }

    @Override
    public List<CasePositionRow> casePosition(Slice slice) {
        MapSqlParameterSource params = SqlWhere.params(slice);
        SqlWhere where = new SqlWhere(params).creditors("creditor_id", slice).in("portfolio_id", slice.portfolios());
        return jdbc.query("""
                select creditor_id, portfolio_id, delay_bucket, status, eligible, owner_type, queue_id, sum(cases),
                       sum(open_balance), sum(eligible_balance)
                from analytics.case_position where %s group by 1, 2, 3, 4, 5, 6, 7
                """.formatted(where), params, (rs, i) -> new CasePositionRow(uuid(rs, 1), uuid(rs, 2), rs.getString(3),
                rs.getString(4), rs.getBoolean(5), rs.getString(6), uuid(rs, 7), rs.getLong(8), rs.getBigDecimal(9),
                rs.getBigDecimal(10)));
    }

    // --------------------------------------------------------------- recuperação

    @Override
    public List<RecoveryRaw> recovery(Slice slice, Dimension groupBy) {
        MapSqlParameterSource params = SqlWhere.params(slice);
        SqlWhere where = new SqlWhere(params).dateBetween("r.fact_date").creditors("r.creditor_id", slice)
                .in("r.portfolio_id", slice.portfolios()).in("r.operator_id", slice.operators())
                .in("r.channel", slice.channels());
        String key = switch (groupBy) {
            case CREDITOR -> "r.creditor_id::text";
            case PORTFOLIO -> "coalesce(r.portfolio_id::text, '-')";
            case OPERATOR, TEAM -> "coalesce(r.operator_id::text, '-')";
            case CHANNEL -> "r.channel";
            case ORIGIN -> "r.origin_group";
            case DELAY_BUCKET -> "r.delay_bucket";
            case DAY, WEEK, MONTH -> dateKey("r.fact_date", groupBy);
            case NONE -> "'ALL'";
        };
        return jdbc.query("""
                select %s as k, sum(r.recovered_gross), sum(r.reversed), sum(r.receipts)
                from analytics.recovery_daily r where %s group by 1 order by 1
                """.formatted(key, where), params, (rs, i) -> new RecoveryRaw(rs.getString(1), rs.getBigDecimal(2),
                rs.getBigDecimal(3), rs.getLong(4)));
    }

    @Override
    public Map<String, BigDecimal> eligibleAtStart(Slice slice, Dimension groupBy) {
        String key = switch (groupBy) {
            case CREDITOR -> "s.creditor_id::text";
            case PORTFOLIO -> "coalesce(s.portfolio_id::text, '-')";
            case OPERATOR, TEAM -> "coalesce(s.assignee_id::text, '-')";
            case NONE -> "'ALL'";
            default -> null;
        };
        if (key == null) {
            return Map.of();
        }
        MapSqlParameterSource params = SqlWhere.params(slice);
        SqlWhere where = new SqlWhere(params).raw("s.eligible_balance > 0").creditors("s.creditor_id", slice)
                .in("s.portfolio_id", slice.portfolios()).in("s.assignee_id", slice.operators());
        Map<String, BigDecimal> result = new HashMap<>();
        jdbc.query("""
                select %s as k, sum(s.eligible_balance)
                from (select distinct on (c.case_id) c.case_id, c.creditor_id, c.portfolio_id, c.assignee_id,
                             c.eligible_balance
                      from analytics.src_case_snapshot c
                      where c.effective_at < :fromTs
                      order by c.case_id, c.effective_at desc) s
                where %s group by 1
                """.formatted(key, where), params, rs -> {
            result.put(rs.getString(1), rs.getBigDecimal(2));
        });
        return result;
    }

    @Override
    public TimeToReceiptRaw timeToReceipt(Slice slice) {
        MapSqlParameterSource params = SqlWhere.params(slice);
        SqlWhere where = cohortWhere(new SqlWhere(params).raw("c.first_receipt_on between :from and :to"), slice);
        return jdbc.queryForObject("""
                select count(*), avg(c.first_receipt_on - c.formalized_on),
                       percentile_cont(0.5) within group (order by c.first_receipt_on - c.formalized_on),
                       percentile_cont(0.9) within group (order by c.first_receipt_on - c.formalized_on)
                from analytics.agreement_cohort c where %s
                """.formatted(where), params, (rs, i) -> new TimeToReceiptRaw(rs.getLong(1), scale2(rs.getBigDecimal(2)),
                scale2(rs.getBigDecimal(3)), scale2(rs.getBigDecimal(4))));
    }

    @Override
    public StatementCost statementCost(LocalDate monthFrom, LocalDate monthTo) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("mf", monthFrom, Types.DATE)
                .addValue("mt", monthTo, Types.DATE);
        return jdbc.queryForObject("""
                select count(*), coalesce(sum(s.payable_amount), 0) from analytics.src_commission_statement s
                where s.period_start between :mf and :mt and s.status in ('APPROVED', 'EXPORTED')
                """, params, (rs, i) -> new StatementCost(rs.getLong(1), rs.getBigDecimal(2)));
    }

    // ----------------------------------------------------------------- conversão

    @Override
    public Map<String, Long> effectiveContacts(Slice slice, Dimension groupBy) {
        String key = switch (groupBy) {
            case CREDITOR -> "i.creditor_id::text";
            case PORTFOLIO -> "i.portfolio_id::text";
            case OPERATOR, TEAM -> "coalesce(i.operator_id::text, '-')";
            case CHANNEL -> "i.channel";
            case DAY, WEEK, MONTH -> dateKey("(i.started_at at time zone :zone)::date", groupBy);
            case NONE -> "'ALL'";
            default -> null;
        };
        if (key == null) {
            return null;
        }
        MapSqlParameterSource params = SqlWhere.params(slice);
        SqlWhere where = new SqlWhere(params).raw("i.effective_contact").instantIn("i.started_at")
                .creditors("i.creditor_id", slice).in("i.portfolio_id", slice.portfolios())
                .in("i.operator_id", slice.operators()).in("i.channel", slice.channels());
        return counts("select %s as k, count(*) from analytics.src_interaction i where %s group by 1"
                .formatted(key, where), params);
    }

    @Override
    public Map<String, Long> proposals(Slice slice, Dimension groupBy) {
        String key = switch (groupBy) {
            case CREDITOR -> "p.creditor_id::text";
            case PORTFOLIO -> "p.portfolio_id::text";
            case OPERATOR, TEAM -> "case when p.actor_type = 'USER' then p.actor_id::text else '-' end";
            case CHANNEL -> "coalesce(p.channel, 'UNKNOWN')";
            case DAY, WEEK, MONTH -> dateKey("(p.created_at at time zone :zone)::date", groupBy);
            case NONE -> "'ALL'";
            default -> null;
        };
        if (key == null) {
            return null;
        }
        MapSqlParameterSource params = SqlWhere.params(slice);
        SqlWhere where = new SqlWhere(params).instantIn("p.created_at").creditors("p.creditor_id", slice)
                .in("p.portfolio_id", slice.portfolios()).in("coalesce(p.channel, 'UNKNOWN')", slice.channels());
        if (slice.operators() != null) {
            where.raw("p.actor_type = 'USER'").in("p.actor_id", slice.operators());
        }
        return counts("select %s as k, count(*) from analytics.src_proposal p where %s group by 1"
                .formatted(key, where), params);
    }

    @Override
    public List<CohortRaw> cohort(Slice slice, Dimension groupBy, Instant asOf) {
        String key = switch (groupBy) {
            case CREDITOR -> "c.creditor_id::text";
            case PORTFOLIO -> "coalesce(c.portfolio_id::text, '-')";
            case OPERATOR, TEAM -> "coalesce(c.operator_id::text, '-')";
            case CHANNEL -> "c.channel";
            case ORIGIN -> "c.origin_group";
            case DAY, WEEK, MONTH -> dateKey("c.formalized_on", groupBy);
            default -> "'ALL'";
        };
        MapSqlParameterSource params = SqlWhere.params(slice).addValue("asOf", SqlWhere.ts(asOf),
                Types.TIMESTAMP_WITH_TIMEZONE);
        SqlWhere where = cohortWhere(new SqlWhere(params).dateBetween("c.formalized_on"), slice);
        return jdbc.query("""
                select %s as k, count(*), sum(c.total_amount), sum(c.paid_net),
                       count(*) filter (where c.first_installment_paid_at is not null),
                       count(*) filter (where c.renegotiation),
                       %s, %s, %s
                from analytics.agreement_cohort c where %s group by 1 order by 1
                """.formatted(key, window(30), window(60), window(90), where), params,
                (rs, i) -> new CohortRaw(rs.getString(1), rs.getLong(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10),
                        rs.getLong(11), rs.getLong(12)));
    }

    /** Maduros (idade ≥ janela) e, entre eles, quebrados até a janela. */
    private static String window(int days) {
        String limit = "c.formalized_at + interval '%d days'".formatted(days);
        return "count(*) filter (where %1$s <= :asOf), count(*) filter (where %1$s <= :asOf and c.broken_at is not null "
                .formatted(limit) + "and c.broken_at <= %s)".formatted(limit);
    }

    @Override
    public InstallmentsRaw installmentsPaid(Slice slice) {
        MapSqlParameterSource params = SqlWhere.params(slice);
        SqlWhere where = cohortWhere(new SqlWhere(params).raw("i.status = 'PAID'").instantIn("i.paid_at"), slice);
        return jdbc.queryForObject("""
                select count(*), coalesce(sum(i.amount), 0)
                from analytics.src_installment i join analytics.agreement_cohort c on c.agreement_id = i.agreement_id
                where %s
                """.formatted(where), params, (rs, i) -> new InstallmentsRaw(rs.getLong(1), rs.getBigDecimal(2)));
    }

    private static SqlWhere cohortWhere(SqlWhere where, Slice slice) {
        return where.creditors("c.creditor_id", slice).in("c.portfolio_id", slice.portfolios())
                .in("c.operator_id", slice.operators()).in("c.channel", slice.channels());
    }

    // ------------------------------------------------------------- produtividade

    @Override
    public List<EffortRaw> effort(Slice slice) {
        MapSqlParameterSource params = SqlWhere.params(slice);
        SqlWhere facts = new SqlWhere(params).dateBetween("f.fact_date").creditors("f.creditor_id", slice)
                .in("f.portfolio_id", slice.portfolios()).in("f.user_id", slice.operators())
                .in("f.channel", slice.channels());
        Map<UUID, long[]> totals = new LinkedHashMap<>();
        jdbc.query("""
                select f.user_id, sum(f.attempts), sum(f.effective_contacts), sum(f.answered_calls), sum(f.talk_seconds)
                from analytics.src_performance_fact f where %s group by 1
                """.formatted(facts), params, rs -> {
            totals.put(uuid(rs, 1), new long[]{0, rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)});
        });
        MapSqlParameterSource workedParams = SqlWhere.params(slice);
        SqlWhere worked = new SqlWhere(workedParams).dateBetween("w.fact_date").creditors("w.creditor_id", slice)
                .in("w.portfolio_id", slice.portfolios()).in("w.user_id", slice.operators())
                .in("w.channel", slice.channels());
        jdbc.query("""
                select w.user_id, count(distinct w.case_id) from analytics.src_worked_case w where %s group by 1
                """.formatted(worked), workedParams, rs -> {
            totals.computeIfAbsent(uuid(rs, 1), k -> new long[5])[0] = rs.getLong(2);
        });
        return totals.entrySet().stream().map(e -> new EffortRaw(e.getKey(), e.getValue()[0], e.getValue()[1],
                e.getValue()[2], e.getValue()[3], e.getValue()[4])).toList();
    }

    @Override
    public Map<UUID, Long> loggedSeconds(Slice slice) {
        MapSqlParameterSource params = SqlWhere.params(slice);
        SqlWhere where = new SqlWhere(params).dateBetween("l.fact_date").in("l.user_id", slice.operators());
        Map<UUID, Long> result = new HashMap<>();
        jdbc.query("select l.user_id, sum(l.seconds) from analytics.src_logged_time l where %s group by 1"
                .formatted(where), params, rs -> {
            result.put(uuid(rs, 1), rs.getLong(2));
        });
        return result;
    }

    @Override
    public Map<UUID, Long> agreementsByOperator(Slice slice) {
        MapSqlParameterSource params = SqlWhere.params(slice);
        SqlWhere where = cohortWhere(new SqlWhere(params).dateBetween("c.formalized_on").raw("c.operator_id is not null"),
                slice);
        Map<UUID, Long> result = new HashMap<>();
        jdbc.query("select c.operator_id, count(*) from analytics.agreement_cohort c where %s group by 1"
                .formatted(where), params, rs -> {
            result.put(uuid(rs, 1), rs.getLong(2));
        });
        return result;
    }

    @Override
    public Map<UUID, BigDecimal> recoveredByOperator(Slice slice) {
        MapSqlParameterSource params = SqlWhere.params(slice);
        SqlWhere where = new SqlWhere(params).dateBetween("r.fact_date").raw("r.operator_id is not null")
                .creditors("r.creditor_id", slice).in("r.portfolio_id", slice.portfolios())
                .in("r.operator_id", slice.operators()).in("r.channel", slice.channels());
        Map<UUID, BigDecimal> result = new HashMap<>();
        jdbc.query("""
                select r.operator_id, sum(r.recovered_gross) - sum(r.reversed) from analytics.recovery_daily r
                where %s group by 1
                """.formatted(where), params, rs -> {
            result.put(uuid(rs, 1), rs.getBigDecimal(2));
        });
        return result;
    }

    // ------------------------------------------------------------ confiabilidade

    @Override
    public ReliabilityRaw reliability(Slice slice) {
        MapSqlParameterSource p = SqlWhere.params(slice);
        String instr = new SqlWhere(p).instantIn("x.created_at").creditors("x.creditor_id", slice).toString();
        long[] instructions = pair("select count(*), count(*) filter (where x.status = 'FAILED') "
                + "from analytics.src_payment_instruction x where " + instr, p);
        Map<String, Long> instructionFailures = counts("select coalesce(x.failure_code, 'UNKNOWN'), count(*) "
                + "from analytics.src_payment_instruction x where x.status = 'FAILED' and " + instr + " group by 1", p);
        String calls = new SqlWhere(p).instantIn("x.created_at").creditors("x.creditor_id", slice).toString();
        long[] call = pair("select count(*), count(*) filter (where x.status = 'FAILED') from analytics.src_call x where "
                + calls, p);
        String imports = new SqlWhere(p).instantIn("x.created_at").creditors("x.creditor_id", slice).toString();
        long[] statementImports = pair("select count(*), count(*) filter (where x.status = 'FAILED') "
                + "from analytics.src_statement_import x where " + imports, p);
        String messages = new SqlWhere(p).raw("x.direction = 'OUTBOUND'").instantIn("x.created_at")
                .creditors("x.creditor_id", slice).in("x.channel", slice.channels()).toString();
        long[] message = pair("select count(*), count(*) filter (where x.status = 'FAILED') "
                + "from analytics.src_message x where " + messages, p);
        Map<String, Long> messageFailures = counts("select coalesce(x.failure_code, 'UNKNOWN'), count(*) "
                + "from analytics.src_message x where x.status = 'FAILED' and " + messages + " group by 1", p);
        String lines = new SqlWhere(p).instantIn("x.created_at").creditors("x.creditor_id", slice).toString();
        long[] line = pair("select count(*), count(*) filter (where x.status = 'IGNORED_DUPLICATE') "
                + "from analytics.src_statement_line x where " + lines, p);
        String divergences = new SqlWhere(p).instantIn("x.created_at").creditors("x.creditor_id", slice).toString();
        Map<String, Long> byType = counts("select x.type, count(*) from analytics.src_divergence x where " + divergences
                + " group by 1", p);
        long opened = byType.values().stream().mapToLong(Long::longValue).sum();
        String openNow = new SqlWhere(p).raw("x.status = 'OPEN'").creditors("x.creditor_id", slice).toString();
        Object[] open = jdbc.queryForObject("select count(*), coalesce(sum(x.amount), 0) from analytics.src_divergence x "
                + "where " + openNow, p, (rs, i) -> new Object[]{rs.getLong(1), rs.getBigDecimal(2)});
        String resolvedWhere = new SqlWhere(p).raw("x.status <> 'OPEN'").instantIn("x.resolved_at")
                .creditors("x.creditor_id", slice).toString();
        Object[] resolved = jdbc.queryForObject("select count(*), percentile_cont(0.5) within group "
                + "(order by extract(epoch from x.resolved_at - x.created_at) / 3600) from analytics.src_divergence x "
                + "where " + resolvedWhere, p, (rs, i) -> new Object[]{rs.getLong(1), scale2(rs.getBigDecimal(2))});
        String sla = new SqlWhere(p).raw("x.event_type = 'conversation.sla_breached'").instantIn("x.occurred_at")
                .creditors("x.creditor_id", slice).in("x.channel", slice.channels()).toString();
        long slaBreaches = single("select count(*) from analytics.src_business_event x where " + sla, p);

        // fila de jobs não tem credor: só para quem vê todos os credores
        boolean jobsVisible = slice.creditors() == null;
        long deadNow = 0;
        long finished = 0;
        long deadInPeriod = 0;
        Map<String, Long> deadByType = Map.of();
        if (jobsVisible) {
            deadByType = counts("select x.type, count(*) from analytics.src_processing_job x where x.status = 'DEAD' "
                    + "group by 1", p);
            deadNow = deadByType.values().stream().mapToLong(Long::longValue).sum();
            long[] jobs = pair("select count(*), count(*) filter (where x.status = 'DEAD') "
                    + "from analytics.src_processing_job x where x.status in ('DONE', 'DEAD') and "
                    + new SqlWhere(p).instantIn("x.finished_at"), p);
            finished = jobs[0];
            deadInPeriod = jobs[1];
        }
        String batches = new SqlWhere(p).raw("x.mode = 'IMPORT'")
                .raw("x.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED')")
                .instantIn("x.finished_at").creditors("x.creditor_id", slice).in("x.portfolio_id", slice.portfolios())
                .toString();
        Object[] batch = jdbc.queryForObject("""
                select count(*), count(*) filter (where x.status = 'FAILED'),
                       percentile_cont(0.5) within group (order by extract(epoch from x.finished_at - x.started_at))
                           filter (where x.started_at is not null),
                       percentile_cont(0.95) within group (order by extract(epoch from x.finished_at - x.started_at))
                           filter (where x.started_at is not null),
                       coalesce(sum(x.rows_read), 0), coalesce(sum(x.rows_with_error), 0)
                from analytics.src_import_batch x where %s
                """.formatted(batches), p, (rs, i) -> new Object[]{rs.getLong(1), rs.getLong(2),
                scale2(rs.getBigDecimal(3)), scale2(rs.getBigDecimal(4)), rs.getLong(5), rs.getLong(6)});
        return new ReliabilityRaw(instructions[0], instructions[1], instructionFailures, call[0], call[1],
                statementImports[0], statementImports[1], message[0], message[1], messageFailures, line[0], line[1],
                byType.getOrDefault("DUPLICATE_PAYMENT", 0L), slaBreaches, opened, byType, (long) open[0],
                (BigDecimal) open[1], (long) resolved[0], (BigDecimal) resolved[1], deadNow, deadByType, finished,
                deadInPeriod, (long) batch[0], (long) batch[1], (BigDecimal) batch[2], (BigDecimal) batch[3],
                (long) batch[4], (long) batch[5]);
    }

    // --------------------------------------------------------------------- apoio

    @Override
    public List<TeamRaw> teams() {
        Map<UUID, Set<UUID>> members = new HashMap<>();
        jdbc.query("select m.team_id, m.user_id from analytics.src_team_member m", new MapSqlParameterSource(), rs -> {
            members.computeIfAbsent(uuid(rs, 1), k -> new HashSet<>()).add(uuid(rs, 2));
        });
        return jdbc.query("select t.team_id, t.name, t.supervisor_id, t.active from analytics.src_team t order by t.name",
                new MapSqlParameterSource(), (rs, i) -> new TeamRaw(uuid(rs, 1), rs.getString(2), uuid(rs, 3),
                        rs.getBoolean(4), Set.copyOf(members.getOrDefault(uuid(rs, 1), Set.of()))));
    }

    @Override
    public Map<String, String> labels(Dimension dimension, Collection<String> keys) {
        List<UUID> ids = keys.stream().filter(k -> k.length() == 36).map(UUID::fromString).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        String sql = switch (dimension) {
            case CREDITOR -> "select creditor_id, name from analytics.src_creditor where creditor_id in (:ids)";
            case PORTFOLIO -> "select portfolio_id, code || ' — ' || name from analytics.src_portfolio "
                    + "where portfolio_id in (:ids)";
            case OPERATOR -> "select user_id, name from analytics.src_user where user_id in (:ids)";
            default -> null;
        };
        if (sql == null) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        jdbc.query(sql, new MapSqlParameterSource("ids", ids), rs -> {
            result.put(rs.getString(1), rs.getString(2));
        });
        return result;
    }

    @Override
    public Map<String, String> queueLabels(Collection<String> queueIds) {
        List<UUID> ids = queueIds.stream().filter(k -> k != null && k.length() == 36).map(UUID::fromString).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        jdbc.query("select queue_id, name from analytics.src_queue where queue_id in (:ids)",
                new MapSqlParameterSource("ids", ids), rs -> {
                    result.put(rs.getString(1), rs.getString(2));
                });
        return result;
    }

    @Override
    public List<FreshnessRaw> freshness() {
        return jdbc.query("""
                select dataset, data_until, refreshed_at, duration_ms, row_count from analytics.refresh_state
                order by dataset
                """, new MapSqlParameterSource(), (rs, i) -> new FreshnessRaw(rs.getString(1),
                instant(rs, 2), instant(rs, 3), rs.getLong(4), rs.getLong(5)));
    }

    // ------------------------------------------------------------------- helpers

    private static String dateKey(String dateExpression, Dimension groupBy) {
        return switch (groupBy) {
            case DAY -> "to_char(%s, 'YYYY-MM-DD')".formatted(dateExpression);
            case WEEK -> "to_char(date_trunc('week', %s), 'YYYY-MM-DD')".formatted(dateExpression);
            default -> "to_char(%s, 'YYYY-MM')".formatted(dateExpression);
        };
    }

    private Map<String, Long> counts(String sql, MapSqlParameterSource params) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query(sql, params, rs -> {
            result.put(rs.getString(1), rs.getLong(2));
        });
        return result;
    }

    private long[] pair(String sql, MapSqlParameterSource params) {
        return jdbc.queryForObject(sql, params, (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)});
    }

    private long single(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    private static UUID uuid(ResultSet rs, int column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_EVEN);
    }
}
