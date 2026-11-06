package br.com.gestorcobranca.shared.jobs;

import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.security.CurrentActor;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
class JdbcJobQueue implements JobQueue {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcJobQueue.class);

    private final JdbcTemplate jdbcTemplate;
    private final CurrentActor currentActor;
    private final JsonCodec json;
    /** Tardio: o worker depende dos handlers, que dependem desta fila. */
    private final ObjectProvider<JobDispatcher> dispatcher;
    private final MeterRegistry meterRegistry;

    JdbcJobQueue(JdbcTemplate jdbcTemplate, CurrentActor currentActor, JsonCodec json,
                 ObjectProvider<JobDispatcher> dispatcher, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentActor = currentActor;
        this.json = json;
        this.dispatcher = dispatcher;
        this.meterRegistry = meterRegistry;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueueAndDispatch(String type, Object payload, String deduplicationKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Execução imediata de job sem transação ativa: " + type);
        }
        UUID id = enqueue(type, payload, null, deduplicationKey);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // só entrega o id ao executor do worker: a thread que confirmou ainda segura a conexão da transação
                // até o fim das sincronizações, e a reivindicação abre a própria transação na thread do worker
                JobDispatcher worker;
                try {
                    worker = dispatcher.getObject();
                } catch (BeansException e) {
                    // contexto em desligamento: o job já está confirmado e o ciclo normal (desta ou de outra
                    // instância) o executa; quem confirmou a transação não recebe erro
                    meterRegistry.counter("jobs.dispatched", "outcome", "rejected").increment();
                    LOG.warn("Execução imediata do job {} ({}) indisponível; fica para o ciclo normal: {}", id, type,
                            e.getClass().getName());
                    return;
                }
                worker.dispatch(id);
            }
        });
        return id;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String type, Object payload, Instant runAt, String deduplicationKey) {
        UUID tenantId = currentActor.tenantId();
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
        // Job imediato usa o relógio do banco, o mesmo do claim (run_at <= now()): com a hora da JVM, uma
        // diferença de relógio entre aplicação e banco deixaria o job "no futuro" até as horas se alcançarem.
        // o índice único parcial (tenant, type, dedup_key) para jobs ativos protege contra corrida
        int inserted = jdbcTemplate.update("""
                        insert into shared.processing_job
                          (id, tenant_id, type, payload, status, run_at, attempts, dedup_key, created_at, created_by)
                        values (?, ?, ?, cast(? as jsonb), 'PENDING', coalesce(?, now()), 0, ?, now(), ?)
                        on conflict do nothing
                        """,
                id, tenantId, type, json.toJson(payload), runAt == null ? null : Timestamp.from(runAt), deduplicationKey, currentActor.actorId());
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
}
