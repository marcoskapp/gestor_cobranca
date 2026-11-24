package br.com.gestorcobranca.identity.infrastructure.persistence.creditor;

import br.com.gestorcobranca.identity.application.port.out.CreditorLoginThrottle;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.ThrottlePolicy.State;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.UnaryOperator;

/**
 * {@code identity.creditor_login_throttle} (RLS por tenant; o tenant é o da transação): cria a linha se não existir e a
 * lê com {@code FOR UPDATE}, serializando tentativas simultâneas do mesmo IP entre instâncias.
 */
@Component
class JdbcCreditorLoginThrottle implements CreditorLoginThrottle {

    private static final String TYPE = "IP";

    private final JdbcTemplate jdbc;

    JdbcCreditorLoginThrottle(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public State update(String keyHash, Instant now, UnaryOperator<State> transition) {
        jdbc.update("""
                insert into identity.creditor_login_throttle (id, tenant_id, key_type, key_hash, window_started_at,
                                                              failures, requests, lock_count, locked_until, updated_at)
                values (?, shared.current_tenant_id(), ?, ?, ?, 0, 0, 0, null, ?)
                on conflict (tenant_id, key_type, key_hash) do nothing
                """, Ids.newId(), TYPE, keyHash, Timestamp.from(now), Timestamp.from(now));
        State current = jdbc.queryForObject("""
                select window_started_at, failures, requests, lock_count, locked_until
                  from identity.creditor_login_throttle
                 where tenant_id = shared.current_tenant_id() and key_type = ? and key_hash = ? for update
                """, (rs, i) -> state(rs), TYPE, keyHash);
        State next = transition.apply(current);
        jdbc.update("""
                update identity.creditor_login_throttle set window_started_at = ?, failures = ?, requests = ?,
                       lock_count = ?, locked_until = ?, updated_at = ?
                 where tenant_id = shared.current_tenant_id() and key_type = ? and key_hash = ?
                """, Timestamp.from(next.windowStartedAt()), next.failures(), next.requests(), next.lockCount(),
                next.lockedUntil() == null ? null : Timestamp.from(next.lockedUntil()), Timestamp.from(now), TYPE,
                keyHash);
        return next;
    }

    @Override
    public int deleteIdleBefore(Instant cutoff, Instant now, int limit) {
        return jdbc.update("""
                delete from identity.creditor_login_throttle
                 where id in (select id from identity.creditor_login_throttle
                               where tenant_id = shared.current_tenant_id() and updated_at < ?
                                 and (locked_until is null or locked_until < ?)
                               limit ?)
                """, Timestamp.from(cutoff), Timestamp.from(now), limit);
    }

    private static State state(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp window = rs.getTimestamp(1);
        Timestamp locked = rs.getTimestamp(5);
        return new State(window == null ? null : window.toInstant(), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                locked == null ? null : locked.toInstant());
    }
}
