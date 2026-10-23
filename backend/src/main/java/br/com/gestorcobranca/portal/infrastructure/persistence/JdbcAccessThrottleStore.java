package br.com.gestorcobranca.portal.infrastructure.persistence;

import br.com.gestorcobranca.portal.application.port.out.AccessThrottleStore;
import br.com.gestorcobranca.portal.domain.service.ThrottlePolicy.KeyType;
import br.com.gestorcobranca.portal.domain.service.ThrottlePolicy.State;
import br.com.gestorcobranca.shared.domain.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.UnaryOperator;

/**
 * Estado do bloqueio progressivo em {@code portal.access_throttle} (RLS por tenant; o tenant da linha
 * é o da transação). Atualização: cria a linha se não existir ({@code on conflict do nothing}) e a lê
 * com {@code FOR UPDATE}, serializando tentativas simultâneas da mesma chave entre instâncias.
 */
@Component
class JdbcAccessThrottleStore implements AccessThrottleStore {

    private final JdbcTemplate jdbc;

    JdbcAccessThrottleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public State current(KeyType type, String keyHash, Instant now) {
        return jdbc.query("""
                        select window_started_at, failures, requests, lock_count, locked_until from portal.access_throttle
                         where tenant_id = shared.current_tenant_id() and key_type = ? and key_hash = ?
                        """, (rs, i) -> state(rs), type.name(), keyHash).stream().findFirst()
                .orElseGet(() -> State.fresh(now));
    }

    @Override
    public State update(KeyType type, String keyHash, Instant now, UnaryOperator<State> transition) {
        jdbc.update("""
                insert into portal.access_throttle (id, tenant_id, key_type, key_hash, window_started_at, failures, requests,
                                                    lock_count, locked_until, updated_at)
                values (?, shared.current_tenant_id(), ?, ?, ?, 0, 0, 0, null, ?)
                on conflict (tenant_id, key_type, key_hash) do nothing
                """, Ids.newId(), type.name(), keyHash, Timestamp.from(now), Timestamp.from(now));
        State current = jdbc.queryForObject("""
                select window_started_at, failures, requests, lock_count, locked_until from portal.access_throttle
                 where tenant_id = shared.current_tenant_id() and key_type = ? and key_hash = ? for update
                """, (rs, i) -> state(rs), type.name(), keyHash);
        State next = transition.apply(current);
        jdbc.update("""
                update portal.access_throttle set window_started_at = ?, failures = ?, requests = ?, lock_count = ?,
                       locked_until = ?, updated_at = ?
                 where tenant_id = shared.current_tenant_id() and key_type = ? and key_hash = ?
                """, Timestamp.from(next.windowStartedAt()), next.failures(), next.requests(), next.lockCount(),
                next.lockedUntil() == null ? null : Timestamp.from(next.lockedUntil()), Timestamp.from(now), type.name(),
                keyHash);
        return next;
    }

    private static State state(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp window = rs.getTimestamp(1);
        Timestamp locked = rs.getTimestamp(5);
        return new State(window == null ? null : window.toInstant(), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                locked == null ? null : locked.toInstant());
    }
}
