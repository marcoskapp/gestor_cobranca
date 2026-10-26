package br.com.gestorcobranca.scoring.infrastructure.persistence;

import br.com.gestorcobranca.scoring.application.port.out.ScoringPendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRows;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Índice único parcial {@code uk_scoring_model_challenger}: no máximo um desafiante por assessoria. */
@Component
class JdbcScoringPendingWorkQueries implements ScoringPendingWorkQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcScoringPendingWorkQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PendingWorkCount challengerAwaitingDecision(UUID userId) {
        return jdbc.queryForObject("""
                select count(*) as total, 0 as overdue, 0 as due_today, min(created_at) as oldest_at,
                       null::date as next_due, case when count(*) = 1 then (array_agg(id))[1] end as single_id
                  from scoring.model
                 where status = 'CHALLENGER' and (trained_by is null or trained_by <> :me)
                """, new MapSqlParameterSource("me", userId), PendingWorkRows.COUNT);
    }
}
