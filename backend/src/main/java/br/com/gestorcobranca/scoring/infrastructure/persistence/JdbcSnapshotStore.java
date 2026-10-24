package br.com.gestorcobranca.scoring.infrastructure.persistence;

import br.com.gestorcobranca.scoring.application.port.out.SnapshotStore;
import br.com.gestorcobranca.scoring.domain.model.FeatureVector;
import br.com.gestorcobranca.scoring.domain.model.TrainingRow;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@link SnapshotStore} em SQL; RLS restringe ao tenant da transação. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcSnapshotStore implements SnapshotStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    JdbcSnapshotStore(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(List<SnapshotRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = rows.stream().map(r -> new MapSqlParameterSource()
                .addValue("id", Ids.newId()).addValue("run", r.runId())
                .addValue("date", r.referenceDate(), Types.DATE)
                .addValue("at", ts(r.referenceAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("caseId", r.caseId()).addValue("debtorId", r.debtorId())
                .addValue("creditorId", r.creditorId()).addValue("portfolioId", r.portfolioId())
                .addValue("assignee", r.assigneeUserId(), Types.OTHER).addValue("status", r.caseStatus())
                .addValue("balance", r.openBalance().setScale(2, RoundingMode.HALF_EVEN), Types.NUMERIC)
                .addValue("features", json.toJson(r.features().toNames()))
                .addValue("champion", r.championModelId())
                .addValue("championP", probability(r.championProbability()), Types.NUMERIC)
                .addValue("championScore", r.championScore()).addValue("band", r.band())
                .addValue("challenger", r.challengerModelId(), Types.OTHER)
                .addValue("challengerP", r.challengerProbability() == null ? null
                        : probability(r.challengerProbability()), Types.NUMERIC)
                .addValue("due", ts(r.outcomeDueAt()), Types.TIMESTAMP_WITH_TIMEZONE))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into scoring.feature_snapshot (tenant_id, id, run_id, reference_date, reference_at, case_id,
                    debtor_id, creditor_id, portfolio_id, assignee_user_id, case_status, open_balance, features,
                    champion_model_id, champion_probability, champion_score, band, challenger_model_id,
                    challenger_probability, outcome_due_at)
                values (shared.current_tenant_id(), :id, :run, :date, :at, :caseId, :debtorId, :creditorId,
                        :portfolioId, :assignee, :status, :balance, cast(:features as jsonb), :champion, :championP,
                        :championScore, :band, :challenger, :challengerP, :due)
                on conflict (tenant_id, case_id, reference_date) do nothing
                """, batch);
    }

    @Override
    public int resolveDue(Instant dueBefore, Instant now, int limit) {
        return jdbc.update("""
                with due as (
                    select s.id from scoring.feature_snapshot s
                     where not s.outcome_resolved and s.outcome_due_at <= :dueBefore
                     order by s.outcome_due_at
                     limit :limit
                     for update skip locked
                ), outcome as (
                    select d.id, count(f.source_event_id) as payments, coalesce(sum(f.amount), 0) as amount
                      from due d
                      join scoring.feature_snapshot s on s.id = d.id
                      left join scoring.case_fact f
                        on f.kind = 'PAYMENT' and f.debtor_id = s.debtor_id and f.portfolio_id = s.portfolio_id
                       and f.effective_at > s.reference_at and f.effective_at <= s.outcome_due_at
                       and not exists (select 1 from scoring.case_fact r
                                        where r.kind = 'PAYMENT_REVERSAL' and r.reference_id = f.reference_id)
                     group by d.id
                )
                update scoring.feature_snapshot s
                   set outcome_resolved = true, outcome_paid = o.payments > 0, outcome_amount = o.amount,
                       outcome_resolved_at = :now
                  from outcome o
                 where s.id = o.id
                """, new MapSqlParameterSource("dueBefore", ts(dueBefore)).addValue("now", ts(now))
                .addValue("limit", limit));
    }

    @Override
    public List<TrainingRow> trainingRows(LocalDate from, LocalDate to, int limit) {
        return jdbc.query("""
                select case_id, reference_date, features, outcome_paid, open_balance, outcome_amount
                  from scoring.feature_snapshot
                 where outcome_resolved and outcome_paid is not null and reference_date between :from and :to
                 order by reference_date desc, case_id
                 limit :limit
                """, new MapSqlParameterSource().addValue("from", from, Types.DATE).addValue("to", to, Types.DATE)
                        .addValue("limit", limit),
                (rs, i) -> new TrainingRow(rs.getObject("case_id", UUID.class),
                        rs.getObject("reference_date", LocalDate.class), features(rs), rs.getBoolean("outcome_paid"),
                        rs.getBigDecimal("open_balance"), rs.getBigDecimal("outcome_amount")));
    }

    @Override
    public List<PopulationRow> population(UUID runId) {
        return jdbc.query("select features, champion_probability from scoring.feature_snapshot where run_id = :run",
                new MapSqlParameterSource("run", runId),
                (rs, i) -> new PopulationRow(features(rs), rs.getBigDecimal("champion_probability").doubleValue()));
    }

    @Override
    public List<OutcomeRow> resolvedOutcomes(LocalDate from, LocalDate to) {
        return jdbc.query("""
                select reference_date, champion_model_id, champion_probability, band, challenger_model_id,
                       challenger_probability, outcome_paid
                  from scoring.feature_snapshot
                 where outcome_resolved and outcome_paid is not null and reference_date between :from and :to
                """, new MapSqlParameterSource().addValue("from", from, Types.DATE).addValue("to", to, Types.DATE),
                (rs, i) -> new OutcomeRow(rs.getObject("reference_date", LocalDate.class),
                        rs.getObject("champion_model_id", UUID.class),
                        rs.getBigDecimal("champion_probability").doubleValue(), rs.getString("band"),
                        rs.getObject("challenger_model_id", UUID.class),
                        rs.getBigDecimal("challenger_probability") == null ? null
                                : rs.getBigDecimal("challenger_probability").doubleValue(),
                        rs.getBoolean("outcome_paid")));
    }

    @Override
    public Map<UUID, Double> portfolioRates(LocalDate from, LocalDate to, int minRows) {
        Map<UUID, Double> rates = new HashMap<>();
        long[] total = new long[2];
        jdbc.query("""
                select portfolio_id, count(*) as n, count(*) filter (where outcome_paid) as paid
                  from scoring.feature_snapshot
                 where outcome_resolved and outcome_paid is not null and reference_date between :from and :to
                 group by portfolio_id
                """, new MapSqlParameterSource().addValue("from", from, Types.DATE).addValue("to", to, Types.DATE), rs -> {
            long n = rs.getLong("n");
            long paid = rs.getLong("paid");
            total[0] += n;
            total[1] += paid;
            if (n >= minRows) {
                rates.put(rs.getObject("portfolio_id", UUID.class), (double) paid / n);
            }
        });
        if (total[0] >= minRows) {
            rates.put(null, (double) total[1] / total[0]);
        }
        return rates;
    }

    @Override
    public boolean hasUnresolved(UUID runId) {
        Boolean exists = jdbc.queryForObject("""
                select exists (select 1 from scoring.feature_snapshot where run_id = :run and not outcome_resolved)
                """, new MapSqlParameterSource("run", runId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public List<CohortAggregate> cohortAggregates(UUID runId) {
        return jdbc.query("""
                select creditor_id, portfolio_id, assignee_user_id, champion_model_id, count(*) as cases,
                       sum(champion_probability) as expected_payers,
                       count(*) filter (where outcome_paid) as realized_payers,
                       sum(champion_probability * open_balance) as weighted_balance,
                       coalesce(sum(outcome_amount), 0) as realized_amount
                  from scoring.feature_snapshot
                 where run_id = :run and outcome_resolved
                 group by creditor_id, portfolio_id, assignee_user_id, champion_model_id
                """, new MapSqlParameterSource("run", runId),
                (rs, i) -> new CohortAggregate(rs.getObject("creditor_id", UUID.class),
                        rs.getObject("portfolio_id", UUID.class), rs.getObject("assignee_user_id", UUID.class),
                        rs.getObject("champion_model_id", UUID.class), rs.getLong("cases"),
                        rs.getBigDecimal("expected_payers").doubleValue(), rs.getLong("realized_payers"),
                        rs.getBigDecimal("weighted_balance"), rs.getBigDecimal("realized_amount")));
    }

    @Override
    public void saveCohorts(List<CohortOutcome> cohorts) {
        if (cohorts.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = cohorts.stream().map(c -> new MapSqlParameterSource()
                .addValue("cohort", c.cohortId()).addValue("run", c.runId())
                .addValue("date", c.referenceDate(), Types.DATE).addValue("days", c.outcomeDays())
                .addValue("creditor", c.creditorId()).addValue("portfolio", c.portfolioId())
                .addValue("assignee", c.assigneeUserId(), Types.OTHER).addValue("model", c.modelId())
                .addValue("modelVersion", c.modelVersion()).addValue("cases", c.cases())
                .addValue("expectedPayers", c.expectedPayers(), Types.NUMERIC)
                .addValue("realizedPayers", c.realizedPayers())
                .addValue("expectedAmount", c.expectedAmount(), Types.NUMERIC)
                .addValue("realizedAmount", c.realizedAmount(), Types.NUMERIC)
                .addValue("at", ts(c.measuredAt()), Types.TIMESTAMP_WITH_TIMEZONE))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into scoring.cohort_outcome (tenant_id, cohort_id, run_id, reference_date, outcome_days,
                    creditor_id, portfolio_id, assignee_user_id, model_id, model_version, cases, expected_payers,
                    realized_payers, expected_amount, realized_amount, measured_at)
                values (shared.current_tenant_id(), :cohort, :run, :date, :days, :creditor, :portfolio, :assignee,
                        :model, :modelVersion, :cases, :expectedPayers, :realizedPayers, :expectedAmount,
                        :realizedAmount, :at)
                on conflict (tenant_id, cohort_id) do update set
                    cases = excluded.cases, expected_payers = excluded.expected_payers,
                    realized_payers = excluded.realized_payers, expected_amount = excluded.expected_amount,
                    realized_amount = excluded.realized_amount, measured_at = excluded.measured_at
                """, batch);
    }

    @Override
    public List<ExpectedRecoveryRow> expectedRecovery(LocalDate from, LocalDate to, Collection<UUID> creditorIds,
                                                      UUID portfolioId, String groupBy) {
        String group = switch (groupBy) {
            case "CREDITOR" -> "c.creditor_id::text";
            case "PORTFOLIO" -> "c.portfolio_id::text";
            case "ASSIGNEE" -> "coalesce(c.assignee_user_id::text, '-')";
            default -> "'ALL'";
        };
        StringBuilder sql = new StringBuilder("select " + group + " as group_key, sum(c.cases) as cases, "
                + "sum(c.expected_payers) as expected_payers, sum(c.realized_payers) as realized_payers, "
                + "sum(c.expected_amount) as expected_amount, sum(c.realized_amount) as realized_amount "
                + "from scoring.cohort_outcome c where c.reference_date between :from and :to");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", from, Types.DATE).addValue("to", to, Types.DATE);
        if (!creditorIds.isEmpty()) {
            sql.append(" and c.creditor_id in (:creditors)");
            p.addValue("creditors", creditorIds);
        }
        if (portfolioId != null) {
            sql.append(" and c.portfolio_id = :portfolio");
            p.addValue("portfolio", portfolioId);
        }
        sql.append(" group by 1 order by 1");
        return jdbc.query(sql.toString(), p, (rs, i) -> new ExpectedRecoveryRow(rs.getString("group_key"),
                rs.getLong("cases"), rs.getBigDecimal("expected_payers"), rs.getLong("realized_payers"),
                rs.getBigDecimal("expected_amount"), rs.getBigDecimal("realized_amount")));
    }

    @Override
    public List<HistoryRow> history(UUID caseId, int limit) {
        return jdbc.query("""
                select reference_date, champion_score, band, champion_model_id,
                       case when outcome_resolved then outcome_paid end as paid
                  from scoring.feature_snapshot
                 where case_id = :caseId
                 order by reference_date desc
                 limit :limit
                """, new MapSqlParameterSource("caseId", caseId).addValue("limit", limit),
                (rs, i) -> new HistoryRow(rs.getObject("reference_date", LocalDate.class), rs.getInt("champion_score"),
                        rs.getString("band"), rs.getObject("champion_model_id", UUID.class),
                        (Boolean) rs.getObject("paid")));
    }

    @Override
    public int purgeResolvedBefore(LocalDate date, int limit) {
        return jdbc.update("""
                delete from scoring.feature_snapshot
                 where id in (select id from scoring.feature_snapshot
                               where outcome_resolved and reference_date < :date limit :limit)
                """, new MapSqlParameterSource().addValue("date", date, Types.DATE).addValue("limit", limit));
    }

    @Override
    public int anonymizeDebtor(UUID debtorId) {
        MapSqlParameterSource p = new MapSqlParameterSource("debtor", debtorId);
        int deleted = jdbc.update("delete from scoring.feature_snapshot where debtor_id = :debtor and not outcome_resolved", p);
        return deleted + jdbc.update("update scoring.feature_snapshot set debtor_id = null where debtor_id = :debtor", p);
    }

    private FeatureVector features(ResultSet rs) throws SQLException {
        return FeatureVector.fromNames(json.toMap(rs.getString("features")));
    }

    private static BigDecimal probability(double p) {
        return BigDecimal.valueOf(p).setScale(6, RoundingMode.HALF_EVEN);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
