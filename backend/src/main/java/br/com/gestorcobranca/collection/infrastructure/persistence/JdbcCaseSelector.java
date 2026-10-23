package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.application.port.out.CaseSelector;
import br.com.gestorcobranca.collection.domain.model.CaseOrdering;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CaseSelector} em SQL nativo: a ordem de entrega é dinâmica e {@code SKIP LOCKED} não é
 * expresso em JPQL. Executa na conexão da transação JPA corrente (o tenant já está definido e o
 * RLS restringe as linhas). Atende ao índice parcial {@code ix_case_queue_available}.
 */
@Component
class JdbcCaseSelector implements CaseSelector {

    private static final String AVAILABLE = """
            select c.id, c.creditor_id from collection.collection_case c
             where c.queue_id = :queueId
               and c.status in ('OPEN', 'IN_NEGOTIATION', 'PROMISE')
               and c.eligible
               and c.assignee_user_id is null
               and c.owner_type = 'QUEUE'
               and (c.next_action_at is null or c.next_action_at <= :now)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCaseSelector(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> lockNextAvailable(UUID queueId, CaseOrdering ordering, Collection<UUID> allowedCreditorIds,
                                            Instant now) {
        if (allowedCreditorIds != null && allowedCreditorIds.isEmpty()) {
            return Optional.empty();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("queueId", queueId).addValue("now", Timestamp.from(now));
        StringBuilder sql = new StringBuilder(AVAILABLE);
        if (allowedCreditorIds != null) {
            sql.append(" and c.creditor_id in (:creditors)");
            params.addValue("creditors", allowedCreditorIds);
        }
        sql.append(orderBy(ordering)).append(" limit 1 for update of c skip locked");
        return jdbc.query(sql.toString(), params, (rs, i) -> rs.getObject("id", UUID.class)).stream().findFirst();
    }

    @Override
    public List<Candidate> lockAvailable(UUID queueId, CaseOrdering ordering, Instant now, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("queueId", queueId).addValue("now", Timestamp.from(now))
                .addValue("limit", limit);
        String sql = AVAILABLE + orderBy(ordering) + " limit :limit for update of c skip locked";
        return jdbc.query(sql, params,
                (rs, i) -> new Candidate(rs.getObject("id", UUID.class), rs.getObject("creditor_id", UUID.class)));
    }

    @Override
    public Optional<UUID> lockOwnDue(UUID userId, Collection<UUID> allowedCreditorIds, Instant now) {
        if (allowedCreditorIds != null && allowedCreditorIds.isEmpty()) {
            return Optional.empty();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId).addValue("now", Timestamp.from(now));
        StringBuilder sql = new StringBuilder("""
                select c.id from collection.collection_case c
                 where c.assignee_user_id = :userId
                   and c.status in ('OPEN', 'IN_NEGOTIATION', 'PROMISE')
                   and c.eligible
                   and c.next_action_at is not null and c.next_action_at <= :now
                """);
        if (allowedCreditorIds != null) {
            sql.append(" and c.creditor_id in (:creditors)");
            params.addValue("creditors", allowedCreditorIds);
        }
        sql.append(" order by c.next_action_at, c.id limit 1 for update of c skip locked");
        return jdbc.query(sql.toString(), params, (rs, i) -> rs.getObject("id", UUID.class)).stream().findFirst();
    }

    /** Retornos da fila já vencidos primeiro; depois a ordem configurada; desempate estável pelo id. */
    private static String orderBy(CaseOrdering ordering) {
        String criteria = switch (ordering == null ? CaseOrdering.OLDEST_OVERDUE : ordering) {
            case OLDEST_OVERDUE -> "c.oldest_due_date asc nulls last";
            case HIGHEST_BALANCE -> "c.eligible_balance desc";
            case LOWEST_BALANCE -> "c.eligible_balance asc";
            case LEAST_RECENTLY_WORKED -> "c.last_interaction_at asc nulls first";
            case HIGHEST_SCORE -> "c.score desc nulls last";
        };
        return " order by (c.next_action_at is null), c.next_action_at, " + criteria + ", c.id";
    }
}
