package br.com.gestorcobranca.assistant.infrastructure.persistence;

import br.com.gestorcobranca.assistant.application.port.out.AssistantPendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRows;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/** Índice: {@code ix_turn_review_queue (tenant_id, status, created_at)}. */
@Component
class JdbcAssistantPendingWorkQueries implements AssistantPendingWorkQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcAssistantPendingWorkQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PendingWorkCount pendingReviews(Set<UUID> creditorScope) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return PendingWorkCount.NONE;
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("""
                select count(*) as total, 0 as overdue, 0 as due_today, min(created_at) as oldest_at,
                       null::date as next_due, null::uuid as single_id
                  from assistant.turn_review
                 where status = 'PENDING'
                """);
        if (creditorScope != null) {
            sql.append(" and creditor_id in (:scope)");
            params.addValue("scope", creditorScope);
        }
        return jdbc.queryForObject(sql.toString(), params, PendingWorkRows.COUNT);
    }
}
