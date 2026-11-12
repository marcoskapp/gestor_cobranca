package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.application.port.out.TierSampleReader;
import br.com.gestorcobranca.incentive.domain.model.tier.PersonSample;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Insumos da classificação: índices {@code ix_quality_evaluation_user} e {@code ix_expected_recovery_user} (por
 * pessoa e data). Mesmas fontes dos indicadores {@code quality.qaScoreAverage} e
 * {@code financial.expectedRecoveryRatio}.
 */
@Repository
class JdbcTierSampleReader implements TierSampleReader {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcTierSampleReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, PersonSample> samples(Collection<UUID> userIds, LocalDate from, LocalDate to) {
        Map<UUID, PersonSample> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("users", userIds)
                .addValue("from", from, Types.DATE).addValue("to", to, Types.DATE);
        jdbc.query("""
                select evaluated_user_id, count(*), avg(score), bool_or(critical_failure)
                  from incentive.quality_evaluation
                 where evaluated_user_id in (:users) and evaluated_on between :from and :to
                 group by evaluated_user_id
                """, params, rs -> {
            UUID user = rs.getObject(1, UUID.class);
            result.put(user, new PersonSample(user, rs.getLong(2), rs.getBigDecimal(3).setScale(2, RoundingMode.HALF_EVEN),
                    rs.getBoolean(4), 0, BigDecimal.ZERO, BigDecimal.ZERO));
        });
        jdbc.query("""
                select user_id, coalesce(sum(cases), 0), coalesce(sum(expected_amount), 0), coalesce(sum(realized_amount), 0)
                  from incentive.expected_recovery_fact
                 where user_id in (:users) and reference_date between :from and :to
                 group by user_id
                """, params, rs -> {
            UUID user = rs.getObject(1, UUID.class);
            PersonSample q = result.getOrDefault(user, PersonSample.empty(user));
            result.put(user, new PersonSample(user, q.evaluations(), q.qualityAverage(), q.criticalFailure(),
                    rs.getLong(2), rs.getBigDecimal(3), rs.getBigDecimal(4)));
        });
        return result;
    }
}
