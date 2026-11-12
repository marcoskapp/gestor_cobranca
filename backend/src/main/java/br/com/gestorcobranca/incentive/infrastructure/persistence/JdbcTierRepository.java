package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.tier.Tier;
import br.com.gestorcobranca.incentive.domain.model.tier.TierAssignment;
import br.com.gestorcobranca.incentive.domain.model.tier.TierMatrix;
import br.com.gestorcobranca.incentive.domain.model.tier.TierPeriodicity;
import br.com.gestorcobranca.incentive.domain.model.tier.TierProposal;
import br.com.gestorcobranca.incentive.domain.model.tier.TierRound;
import br.com.gestorcobranca.incentive.domain.model.tier.TierRoundStatus;
import br.com.gestorcobranca.incentive.domain.model.tier.TierSettings;
import br.com.gestorcobranca.incentive.domain.repository.TierRepository;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Classificação de desempenho em SQL (V1892); tenant pela RLS ({@code shared.current_tenant_id()}). */
@Repository
class JdbcTierRepository implements TierRepository {

    private static final TypeReference<Map<String, Map<String, String>>> MATRIX = new TypeReference<>() {
    };

    private static final String ROUND_COLUMNS = """
            id, period_from, period_to, origin, settings::text as settings, review_deadline, status, created_at,
            created_by, published_at, published_by, valid_from, expired_at""";

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    JdbcTierRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Cópia dos parâmetros em JSON (rodada) — mesmos nomes da API. */
    record SettingsJson(int windowDays, int minEvaluations, int minCases, BigDecimal qualityGate,
                        Map<String, Map<String, String>> matrix, String defaultTier, String periodicity,
                        int reviewBusinessDays) {

        static SettingsJson of(TierSettings s) {
            return new SettingsJson(s.windowDays(), s.minEvaluations(), s.minCases(), s.qualityGate(),
                    s.matrix().toMap(), s.defaultTier().name(), s.periodicity().name(), s.reviewBusinessDays());
        }

        TierSettings toSettings() {
            return new TierSettings(windowDays, minEvaluations, minCases, qualityGate, TierMatrix.of(matrix),
                    Tier.valueOf(defaultTier), TierPeriodicity.valueOf(periodicity), reviewBusinessDays);
        }
    }

    @Override
    public Optional<TierSettings> settings() {
        return jdbc.query("""
                select window_days, min_evaluations, min_cases, quality_gate, matrix::text as matrix, default_tier,
                       periodicity, review_business_days
                  from incentive.tier_settings
                """, new MapSqlParameterSource(), (rs, i) -> new TierSettings(rs.getInt("window_days"),
                rs.getInt("min_evaluations"), rs.getInt("min_cases"), rs.getBigDecimal("quality_gate"),
                TierMatrix.of(json.fromJson(rs.getString("matrix"), MATRIX)), Tier.valueOf(rs.getString("default_tier")),
                TierPeriodicity.valueOf(rs.getString("periodicity")), rs.getInt("review_business_days")))
                .stream().findFirst();
    }

    @Override
    public void saveSettings(TierSettings s, UUID by, Instant at) {
        jdbc.update("""
                insert into incentive.tier_settings (tenant_id, window_days, min_evaluations, min_cases, quality_gate,
                    matrix, default_tier, periodicity, review_business_days, updated_at, updated_by)
                values (shared.current_tenant_id(), :window, :minEvaluations, :minCases, :gate, cast(:matrix as jsonb),
                        :defaultTier, :periodicity, :reviewDays, :at, :by)
                on conflict (tenant_id) do update set
                    window_days = excluded.window_days, min_evaluations = excluded.min_evaluations,
                    min_cases = excluded.min_cases, quality_gate = excluded.quality_gate, matrix = excluded.matrix,
                    default_tier = excluded.default_tier, periodicity = excluded.periodicity,
                    review_business_days = excluded.review_business_days, updated_at = excluded.updated_at,
                    updated_by = excluded.updated_by
                """, new MapSqlParameterSource().addValue("window", s.windowDays())
                .addValue("minEvaluations", s.minEvaluations()).addValue("minCases", s.minCases())
                .addValue("gate", s.qualityGate(), Types.NUMERIC).addValue("matrix", json.toJson(s.matrix().toMap()))
                .addValue("defaultTier", s.defaultTier().name()).addValue("periodicity", s.periodicity().name())
                .addValue("reviewDays", s.reviewBusinessDays()).addValue("at", ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("by", by, Types.OTHER));
    }

    @Override
    public void insertRound(TierRound round, Collection<TierAssignment> assignments) {
        jdbc.update("""
                insert into incentive.tier_round (id, tenant_id, period_from, period_to, origin, settings,
                    review_deadline, status, created_at, created_by)
                values (:id, shared.current_tenant_id(), :from, :to, :origin, cast(:settings as jsonb), :deadline,
                        :status, :at, :by)
                """, new MapSqlParameterSource().addValue("id", round.id(), Types.OTHER)
                .addValue("from", round.periodFrom(), Types.DATE).addValue("to", round.periodTo(), Types.DATE)
                .addValue("origin", round.trigger().name())
                .addValue("settings", json.toJson(SettingsJson.of(round.settings())))
                .addValue("deadline", round.reviewDeadline(), Types.DATE).addValue("status", round.status().name())
                .addValue("at", ts(round.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("by", round.createdBy(), Types.OTHER));
        if (assignments.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                insert into incentive.tier_assignment (round_id, user_id, tenant_id, quality_quartile,
                    efficiency_quartile, quality_gate_failed, evaluations, quality_average, cases, recovery_ratio,
                    proposed_tier, final_tier)
                values (:round, :user, shared.current_tenant_id(), :q, :e, :gate, :evaluations, :average, :cases,
                        :ratio, :proposed, :final)
                """, assignments.stream().map(a -> {
            TierProposal p = a.proposal();
            return new MapSqlParameterSource().addValue("round", a.roundId(), Types.OTHER)
                    .addValue("user", p.userId(), Types.OTHER).addValue("q", p.qualityQuartile(), Types.SMALLINT)
                    .addValue("e", p.efficiencyQuartile(), Types.SMALLINT).addValue("gate", p.qualityGateFailed())
                    .addValue("evaluations", p.evaluations()).addValue("average", p.qualityAverage(), Types.NUMERIC)
                    .addValue("cases", p.cases()).addValue("ratio", p.recoveryRatio(), Types.NUMERIC)
                    .addValue("proposed", p.proposedTier().name()).addValue("final", a.finalTier().name());
        }).toArray(MapSqlParameterSource[]::new));
    }

    @Override
    public Optional<TierRound> findRound(UUID id) {
        return jdbc.query("select " + ROUND_COLUMNS + " from incentive.tier_round where id = :id",
                new MapSqlParameterSource("id", id), roundMapper()).stream().findFirst();
    }

    @Override
    public Optional<TierRound> findRoundForShare(UUID id) {
        return jdbc.query("select " + ROUND_COLUMNS + " from incentive.tier_round where id = :id for share",
                new MapSqlParameterSource("id", id), roundMapper()).stream().findFirst();
    }

    @Override
    public Optional<TierRound> openRound() {
        return jdbc.query("select " + ROUND_COLUMNS + " from incentive.tier_round where status = 'PROPOSED'",
                new MapSqlParameterSource(), roundMapper()).stream().findFirst();
    }

    @Override
    public Optional<TierRound> lastPublished() {
        return jdbc.query("select " + ROUND_COLUMNS + " from incentive.tier_round where status = 'PUBLISHED' "
                        + "order by published_at desc limit 1", new MapSqlParameterSource(), roundMapper())
                .stream().findFirst();
    }

    @Override
    public List<TierRound> rounds(int limit) {
        return jdbc.query("select " + ROUND_COLUMNS + " from incentive.tier_round order by created_at desc limit :limit",
                new MapSqlParameterSource("limit", limit), roundMapper());
    }

    @Override
    public boolean scheduledRoundSince(Instant since) {
        Boolean exists = jdbc.queryForObject("""
                select exists (select 1 from incentive.tier_round where origin = 'SCHEDULED' and created_at >= :since)
                """, new MapSqlParameterSource().addValue("since", ts(since), Types.TIMESTAMP_WITH_TIMEZONE),
                Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public boolean updateStatusFromProposed(TierRound round) {
        return jdbc.update("""
                update incentive.tier_round
                   set status = :status, published_at = :publishedAt, published_by = :publishedBy,
                       valid_from = :validFrom, expired_at = :expiredAt
                 where id = :id and status = 'PROPOSED'
                """, new MapSqlParameterSource().addValue("id", round.id(), Types.OTHER)
                .addValue("status", round.status().name())
                .addValue("publishedAt", ts(round.publishedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("publishedBy", round.publishedBy(), Types.OTHER)
                .addValue("validFrom", round.validFrom(), Types.DATE)
                .addValue("expiredAt", ts(round.expiredAt()), Types.TIMESTAMP_WITH_TIMEZONE)) == 1;
    }

    @Override
    public List<TierAssignment> assignments(UUID roundId) {
        return jdbc.query("select * from incentive.tier_assignment where round_id = :round",
                new MapSqlParameterSource("round", roundId), ASSIGNMENT);
    }

    @Override
    public Map<UUID, List<TierAssignment>> assignments(Collection<UUID> roundIds) {
        if (roundIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.query("select * from incentive.tier_assignment where round_id in (:rounds)",
                        new MapSqlParameterSource("rounds", roundIds), ASSIGNMENT).stream()
                .collect(Collectors.groupingBy(TierAssignment::roundId));
    }

    @Override
    public Optional<TierAssignment> assignment(UUID roundId, UUID userId) {
        return jdbc.query("select * from incentive.tier_assignment where round_id = :round and user_id = :user",
                new MapSqlParameterSource().addValue("round", roundId).addValue("user", userId), ASSIGNMENT)
                .stream().findFirst();
    }

    @Override
    public Optional<TierAssignment> assignmentForUpdate(UUID roundId, UUID userId) {
        return jdbc.query("select * from incentive.tier_assignment where round_id = :round and user_id = :user"
                        + " for no key update",
                new MapSqlParameterSource().addValue("round", roundId).addValue("user", userId), ASSIGNMENT)
                .stream().findFirst();
    }

    @Override
    public void updateAssignment(TierAssignment a) {
        jdbc.update("""
                update incentive.tier_assignment
                   set final_tier = :final, adjusted_by = :by, adjusted_at = :at, adjustment_reason = :reason
                 where round_id = :round and user_id = :user
                """, new MapSqlParameterSource().addValue("round", a.roundId(), Types.OTHER)
                .addValue("user", a.userId(), Types.OTHER).addValue("final", a.finalTier().name())
                .addValue("by", a.adjustedBy(), Types.OTHER)
                .addValue("at", ts(a.adjustedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("reason", a.adjustmentReason()));
    }

    private RowMapper<TierRound> roundMapper() {
        return (rs, i) -> new TierRound(rs.getObject("id", UUID.class), rs.getObject("period_from", LocalDate.class),
                rs.getObject("period_to", LocalDate.class), TierRound.Trigger.valueOf(rs.getString("origin")),
                json.fromJson(rs.getString("settings"), SettingsJson.class).toSettings(),
                rs.getObject("review_deadline", LocalDate.class), instant(rs, "created_at"),
                rs.getObject("created_by", UUID.class), TierRoundStatus.valueOf(rs.getString("status")),
                instant(rs, "published_at"), rs.getObject("published_by", UUID.class),
                rs.getObject("valid_from", LocalDate.class), instant(rs, "expired_at"));
    }

    private static final RowMapper<TierAssignment> ASSIGNMENT = (rs, i) -> {
        TierProposal proposal = new TierProposal(rs.getObject("user_id", UUID.class), integer(rs, "quality_quartile"),
                integer(rs, "efficiency_quartile"), rs.getBoolean("quality_gate_failed"), rs.getLong("evaluations"),
                rs.getBigDecimal("quality_average"), rs.getLong("cases"), rs.getBigDecimal("recovery_ratio"),
                Tier.valueOf(rs.getString("proposed_tier")));
        return new TierAssignment(rs.getObject("round_id", UUID.class), proposal, Tier.valueOf(rs.getString("final_tier")),
                rs.getObject("adjusted_by", UUID.class), instant(rs, "adjusted_at"), rs.getString("adjustment_reason"));
    };

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
