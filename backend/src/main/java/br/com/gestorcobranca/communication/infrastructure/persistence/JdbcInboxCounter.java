package br.com.gestorcobranca.communication.infrastructure.persistence;

import br.com.gestorcobranca.communication.application.port.out.InboxCounter;
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

/** Contagens por aba com {@code count(*) filter}, sob RLS da transação corrente. */
@Component
class JdbcInboxCounter implements InboxCounter {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcInboxCounter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Counts count(Set<UUID> allowedCreditorIds, UUID userId, String channel, boolean awaitingOnly,
                        Map<String, Instant> breachedBefore) {
        if (allowedCreditorIds != null && allowedCreditorIds.isEmpty()) {
            return new Counts(0, 0, 0, 0, 0);
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("me", userId);
        List<String> breached = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, Instant> e : breachedBefore.entrySet()) {
            breached.add("(channel = :bch" + i + " and awaiting_since <= :bat" + i + ")");
            params.addValue("bch" + i, e.getKey()).addValue("bat" + i, Timestamp.from(e.getValue()));
            i++;
        }
        StringBuilder sql = new StringBuilder("""
                select count(*) filter (where owner_type = 'USER' and owner_user_id = :me) as mine,
                       count(*) filter (where owner_type in ('QUEUE', 'NONE')) as queue,
                       count(*) filter (where owner_type = 'ASSISTANT') as assistant,
                       count(*) as total,
                       count(*) filter (where awaiting_since is not null and (%s)) as breached
                  from communication.conversation
                 where status = 'OPEN'
                """.formatted(breached.isEmpty() ? "false" : String.join(" or ", breached)));
        if (allowedCreditorIds != null) {
            sql.append(" and creditor_id in (:scope)");
            params.addValue("scope", allowedCreditorIds);
        }
        if (channel != null) {
            sql.append(" and channel = :channel");
            params.addValue("channel", channel);
        }
        if (awaitingOnly) {
            sql.append(" and awaiting_since is not null");
        }
        return jdbc.queryForObject(sql.toString(), params, (rs, n) -> new Counts(rs.getLong("mine"),
                rs.getLong("queue"), rs.getLong("assistant"), rs.getLong("total"), rs.getLong("breached")));
    }
}
