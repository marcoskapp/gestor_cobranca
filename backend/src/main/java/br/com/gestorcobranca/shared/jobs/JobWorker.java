package br.com.gestorcobranca.shared.jobs;

import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.MdcKeys;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Consome {@code shared.processing_job}. Cada instância reivindica jobs com
 * {@code FOR UPDATE SKIP LOCKED} (sem disputa entre instâncias), respeitando um limite de
 * concorrência local. A execução do handler e a marcação {@code DONE} ocorrem na mesma
 * transação, no tenant do job.
 * <p>
 * Falhas transitórias são reagendadas com backoff exponencial (10 s, 20 s, 40 s… até 1 h);
 * falhas permanentes ou excesso de tentativas movem o job para {@code DEAD}, contabilizado na
 * métrica {@code jobs.executed{outcome=dead}} com alarme no CloudWatch.
 */
@Component
class JobWorker {

    private static final Logger LOG = LoggerFactory.getLogger(JobWorker.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transaction;
    private final TenantExecution tenantExecution;
    private final JsonCodec json;
    private final MeterRegistry meterRegistry;
    private final Map<String, JobHandler<?>> handlers;
    private final ExecutorService executor;
    private final Semaphore capacity;
    private final int concurrency;
    private final Duration lease;
    private final String workerId;

    JobWorker(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager, TenantExecution tenantExecution,
              JsonCodec json, MeterRegistry meterRegistry, List<JobHandler<?>> handlers,
              @Value("${app.jobs.concurrency:8}") int concurrency,
              @Value("${app.jobs.lease:PT15M}") Duration lease) {
        this.jdbcTemplate = jdbcTemplate;
        this.transaction = new TransactionTemplate(transactionManager);
        this.tenantExecution = tenantExecution;
        this.json = json;
        this.meterRegistry = meterRegistry;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(JobHandler::type, Function.identity()));
        this.concurrency = concurrency;
        this.capacity = new Semaphore(concurrency);
        this.executor = Executors.newFixedThreadPool(concurrency, Thread.ofPlatform().name("job-worker-", 0).factory());
        this.lease = lease;
        this.workerId = ManagementFactory.getRuntimeMXBean().getName();
        Gauge.builder("jobs.pending", jdbcTemplate, jdbc -> {
                    Long count = jdbc.queryForObject(
                            "select count(*) from shared.processing_job where status = 'PENDING' and run_at <= now()", Long.class);
                    return count == null ? 0 : count;
                })
                .description("Jobs prontos aguardando execução")
                .register(meterRegistry);
        Gauge.builder("jobs.dead", jdbcTemplate, jdbc -> {
                    Long count = jdbc.queryForObject("select count(*) from shared.processing_job where status = 'DEAD'", Long.class);
                    return count == null ? 0 : count;
                })
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.jobs.poll-interval:PT1S}")
    void poll() {
        int available = capacity.availablePermits();
        if (available == 0) {
            return;
        }
        List<ClaimedJob> claimed = claim(available);
        for (ClaimedJob job : claimed) {
            capacity.acquireUninterruptibly();
            executor.execute(() -> {
                try {
                    execute(job);
                } finally {
                    capacity.release();
                }
            });
        }
    }

    private List<ClaimedJob> claim(int limit) {
        return transaction.execute(status -> jdbcTemplate.query("""
                        update shared.processing_job j
                           set status = 'RUNNING', attempts = attempts + 1, locked_by = ?,
                               locked_until = now() + cast(? as interval), started_at = now()
                         where j.id in (select id from shared.processing_job
                                         where status = 'PENDING' and run_at <= now()
                                         order by priority desc, run_at
                                         limit ? for update skip locked)
                        returning j.id, j.tenant_id, j.type, j.payload::text, j.attempts
                        """,
                (rs, i) -> new ClaimedJob(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getInt(5)),
                workerId, lease.toSeconds() + " seconds", limit));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void execute(ClaimedJob job) {
        JobHandler handler = handlers.get(job.type());
        MDC.put(MdcKeys.PROCESS, "job:" + job.type());
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            if (handler == null) {
                outcome = "dead";
                markDead(job, "Nenhum handler registrado para o tipo " + job.type());
                return;
            }
            Object payload = json.fromJson(job.payload(), handler.payloadType());
            JobContext context = new JobContext(job.id(), job.tenantId(), job.type(), job.attempt());
            tenantExecution.inTenant(job.tenantId(), "job:" + job.type(), () -> {
                handler.handle(context, payload);
                jdbcTemplate.update("update shared.processing_job set status = 'DONE', finished_at = now(), last_error = null where id = ?",
                        job.id());
            });
        } catch (RuntimeException e) {
            boolean permanent = handler != null && handler.isPermanent(e);
            int maxAttempts = handler == null ? 1 : handler.maxAttempts();
            if (permanent || job.attempt() >= maxAttempts) {
                outcome = "dead";
                LOG.error("Job {} ({}) movido para DEAD após {} tentativa(s)", job.id(), job.type(), job.attempt(), e);
                markDead(job, describe(e));
            } else {
                outcome = "retry";
                long backoffSeconds = Math.min(3600, 10L * (1L << Math.min(job.attempt() - 1, 9)));
                LOG.warn("Job {} ({}) falhou na tentativa {}; nova tentativa em {}s: {}", job.id(), job.type(),
                        job.attempt(), backoffSeconds, e.toString());
                transaction.executeWithoutResult(s -> jdbcTemplate.update("""
                        update shared.processing_job
                           set status = 'PENDING', run_at = now() + cast(? as interval), locked_by = null,
                               locked_until = null, last_error = ?
                         where id = ?
                        """, backoffSeconds + " seconds", describe(e), job.id()));
            }
        } finally {
            sample.stop(meterRegistry.timer("jobs.duration", "type", job.type(), "outcome", outcome));
            meterRegistry.counter("jobs.executed", "type", job.type(), "outcome", outcome).increment();
            MDC.remove(MdcKeys.PROCESS);
        }
    }

    private void markDead(ClaimedJob job, String error) {
        transaction.executeWithoutResult(s -> jdbcTemplate.update("""
                update shared.processing_job
                   set status = 'DEAD', finished_at = now(), locked_by = null, locked_until = null, last_error = ?
                 where id = ?
                """, error, job.id()));
    }

    /** Recupera jobs de instâncias que caíram durante a execução (lease expirado). */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    @SchedulerLock(name = "jobs.recover-expired-leases", lockAtMostFor = "PT2M")
    void recoverExpiredLeases() {
        Integer recovered = transaction.execute(s -> jdbcTemplate.update("""
                update shared.processing_job
                   set status = 'PENDING', locked_by = null, locked_until = null,
                       last_error = 'lease expirado: instância interrompida durante a execução'
                 where status = 'RUNNING' and locked_until < now()
                """));
        if (recovered != null && recovered > 0) {
            meterRegistry.counter("jobs.recovered").increment(recovered);
            LOG.warn("{} job(s) com lease expirado retornaram para a fila", recovered);
        }
    }

    private static String describe(RuntimeException e) {
        String message = e.getClass().getName() + ": " + e.getMessage();
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            LOG.warn("Jobs ainda em execução no desligamento; serão recuperados pelo lease ({} workers)", concurrency);
            executor.shutdownNow();
        }
    }

    private record ClaimedJob(UUID id, UUID tenantId, String type, String payload, int attempt) {
    }
}
