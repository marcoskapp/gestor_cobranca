package br.com.gestorcobranca.communication.infrastructure.persistence;

import br.com.gestorcobranca.communication.application.port.out.CommunicationPendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRows;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Índices: {@code ix_conversation_owner_user} (abertas por operador), {@code ix_conversation_inbox}
 * (status, dono, espera) e {@code ix_template_draft_approval} (V1090).
 */
@Component
class JdbcCommunicationPendingWorkQueries implements CommunicationPendingWorkQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCommunicationPendingWorkQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PendingWorkCount awaitingReply(Set<UUID> creditorScope, UUID ownerUserId, Map<String, Instant> breachedBefore) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return PendingWorkCount.NONE;
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> breached = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, Instant> e : breachedBefore.entrySet()) {
            breached.add("(channel = :bch" + i + " and awaiting_since <= :bat" + i + ")");
            params.addValue("bch" + i, e.getKey()).addValue("bat" + i, Timestamp.from(e.getValue()));
            i++;
        }
        StringBuilder sql = new StringBuilder("""
                select count(*) as total, count(*) filter (where %s) as overdue, 0 as due_today,
                       min(awaiting_since) as oldest_at, null::date as next_due,
                       case when count(*) = 1 then (array_agg(id))[1] end as single_id
                  from communication.conversation
                 where status = 'OPEN' and awaiting_since is not null
                """.formatted(breached.isEmpty() ? "false" : String.join(" or ", breached)));
        if (ownerUserId != null) {
            sql.append(" and owner_type = 'USER' and owner_user_id = :me");
            params.addValue("me", ownerUserId);
        } else {
            sql.append(" and owner_type in ('QUEUE', 'NONE')");
        }
        if (creditorScope != null) {
            sql.append(" and creditor_id in (:scope)");
            params.addValue("scope", creditorScope);
        }
        return jdbc.queryForObject(sql.toString(), params, PendingWorkRows.COUNT);
    }

    @Override
    public PendingWorkCount templatesAwaitingApproval(Set<UUID> creditorScope) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return PendingWorkCount.NONE;
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("""
                select count(*) as total, 0 as overdue, 0 as due_today, min(updated_at) as oldest_at,
                       null::date as next_due, case when count(*) = 1 then (array_agg(id))[1] end as single_id
                  from communication.message_template
                 where status = 'DRAFT' and approval_status in ('PENDING', 'APPROVED')
                """);
        if (creditorScope != null) {
            sql.append(" and creditor_id in (:scope)");
            params.addValue("scope", creditorScope);
        }
        return jdbc.queryForObject(sql.toString(), params, PendingWorkRows.COUNT);
    }
}
