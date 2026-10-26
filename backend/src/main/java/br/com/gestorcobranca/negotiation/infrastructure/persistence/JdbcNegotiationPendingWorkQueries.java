package br.com.gestorcobranca.negotiation.infrastructure.persistence;

import br.com.gestorcobranca.negotiation.application.port.out.NegotiationPendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRows;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** Índice: {@code ix_proposal_actor (tenant_id, actor_type, actor_id, created_at)}. */
@Component
class JdbcNegotiationPendingWorkQueries implements NegotiationPendingWorkQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcNegotiationPendingWorkQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PendingWorkCount ownProposals(Set<UUID> creditorScope, UUID userId, String status, LocalDate today) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return PendingWorkCount.NONE;
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("me", userId).addValue("status", status)
                .addValue("today", Date.valueOf(today));
        StringBuilder sql = new StringBuilder("""
                select count(*) as total,
                       count(*) filter (where valid_until < :today) as overdue,
                       count(*) filter (where valid_until = :today) as due_today,
                       min(coalesce(accepted_at, decided_at, created_at)) as oldest_at,
                       min(valid_until) as next_due,
                       case when count(*) = 1 then (array_agg(id))[1] end as single_id
                  from negotiation.proposal
                 where actor_type = 'USER' and actor_id = :me and status = :status
                """);
        if (creditorScope != null) {
            sql.append(" and creditor_id in (:scope)");
            params.addValue("scope", creditorScope);
        }
        return jdbc.queryForObject(sql.toString(), params, PendingWorkRows.COUNT);
    }
}
