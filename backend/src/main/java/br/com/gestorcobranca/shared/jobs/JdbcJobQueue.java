package br.com.gestorcobranca.shared.jobs;

import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
class JdbcJobQueue implements JobQueue {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentActor currentActor;
    private final JsonCodec json;
    private final Clock clock;
    private final Duration lease;

    JdbcJobQueue(JdbcTemplate jdbcTemplate, CurrentActor currentActor, JsonCodec json, Clock clock,
                 @Value("${app.jobs.lease:PT15M}") Duration lease) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentActor = currentActor;
        this.json = json;
        this.clock = clock;
        this.lease = lease;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String type, Object payload, Instant runAt, String deduplicationKey) {
        UUID tenantId = currentActor.tenantId();
        Instant when = runAt == null ? Instant.now(clock) : runAt;
        if (deduplicationKey != null) {
            List<UUID> existing = jdbcTemplate.queryForList("""
                    select id from shared.processing_job
                    where tenant_id = ? and type = ? and dedup_key = ? and status in ('PENDING', 'RUNNING')
                    limit 1
                    """, UUID.class, tenantId, type, deduplicationKey);
            if (!existing.isEmpty()) {
                return existing.getFirst();
            }
        }
        UUID id = Ids.newId();
        // o índice único parcial (tenant, type, dedup_key) para jobs ativos protege contra corrida
        int inserted = jdbcTemplate.update("""
                        insert into shared.processing_job
                          (id, tenant_id, type, payload, status, run_at, attempts, dedup_key, created_at, created_by)
                        values (?, ?, ?, cast(? as jsonb), 'PENDING', ?, 0, ?, now(), ?)
                        on conflict do nothing
                        """,
                id, tenantId, type, json.toJson(payload), Timestamp.from(when), deduplicationKey, currentActor.actorId());
        if (inserted == 0) {
            return jdbcTemplate.queryForObject("""
                    select id from shared.processing_job
                    where tenant_id = ? and type = ? and dedup_key = ? and status in ('PENDING', 'RUNNING')
                    """, UUID.class, tenantId, type, deduplicationKey);
        }
        return id;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelPending(String type, String deduplicationKey) {
        return jdbcTemplate.update("""
                update shared.processing_job set status = 'CANCELLED', finished_at = now()
                where tenant_id = ? and type = ? and dedup_key = ? and status = 'PENDING'
                """, currentActor.tenantId(), type, deduplicationKey);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renewLease(UUID jobId, int attempt) {
        // a tentativa identifica a reivindicação: um job recuperado e reivindicado de novo tem outra
        return jdbcTemplate.update("""
                update shared.processing_job set locked_until = now() + cast(? as interval)
                where id = ? and status = 'RUNNING' and attempts = ?
                """, lease.toSeconds() + " seconds", jobId, attempt) == 1;
    }
}
