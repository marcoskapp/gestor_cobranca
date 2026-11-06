package br.com.gestorcobranca.shared.jobs;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.SensitiveDataMasker;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Consome {@code shared.processing_job}. Cada instância reivindica jobs com
 * {@code FOR UPDATE SKIP LOCKED} (sem disputa entre instâncias), respeitando um limite de
 * concorrência local. A execução do handler e a marcação {@code DONE} ocorrem na mesma
 * transação, no tenant do job (ou em sequência, para handler não transacional).
 * <p>
 * Posse do job (decisão 852): enquanto o handler roda, o lease é renovado a cada
 * {@code app.jobs.heartbeat-interval}; toda baixa (DONE, nova tentativa, DEAD) confere que esta
 * instância ainda detém aquela tentativa ({@code locked_by} e {@code attempts}). Se outro worker
 * reivindicou o job (lease expirado), a execução antiga é desfeita ({@code outcome=lease_lost}).
 * Job reivindicado de novo além de {@link JobHandler#maxAttempts()} (quedas sucessivas da
 * instância) vai para DEAD sem rodar.
 * <p>
 * Execução imediata ({@link JobQueue#enqueueAndDispatch}, {@link #dispatch}): depois do commit, o job recém-criado
 * é reivindicado pelo id, com o mesmo {@code UPDATE … SKIP LOCKED} do ciclo normal, e executado num executor próprio
 * ({@code app.jobs.dispatch-concurrency}), separado do ciclo normal para que jobs longos não atrasem comandos curtos.
 * Sem vaga nesse executor, ou com o job já reivindicado, nada acontece: o ciclo normal o executa
 * ({@code jobs.dispatched{outcome}}).
 * <p>
 * Falhas transitórias são reagendadas com backoff exponencial (10 s, 20 s, 40 s… até 1 h);
 * falhas permanentes ou excesso de tentativas movem o job para {@code DEAD}, contabilizado na
 * métrica {@code jobs.executed{outcome=dead}} com alarme no CloudWatch.
 */
@Component
class JobWorker implements JobDispatcher {

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
    /** Execução imediata pedida depois do commit (separada do ciclo normal). */
    private final ExecutorService dispatchExecutor;
    private final Semaphore dispatchCapacity;
    private final Duration lease;
    private final String workerId;
    /** Jobs em execução nesta instância (id → tentativa reivindicada), para renovar o lease. */
    private final Map<UUID, Integer> running = new ConcurrentHashMap<>();

    JobWorker(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager, TenantExecution tenantExecution,
              JsonCodec json, MeterRegistry meterRegistry, List<JobHandler<?>> handlers,
              @Value("${app.jobs.concurrency:8}") int concurrency,
              @Value("${app.jobs.lease:PT15M}") Duration lease,
              @Value("${app.jobs.dispatch-concurrency:8}") int dispatchConcurrency) {
        this.jdbcTemplate = jdbcTemplate;
        this.transaction = new TransactionTemplate(transactionManager);
        this.tenantExecution = tenantExecution;
        this.json = json;
        this.meterRegistry = meterRegistry;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(JobHandler::type, Function.identity()));
        this.concurrency = concurrency;
        this.capacity = new Semaphore(concurrency);
        this.executor = Executors.newFixedThreadPool(concurrency, Thread.ofPlatform().name("job-worker-", 0).factory());
        this.dispatchCapacity = new Semaphore(dispatchConcurrency);
        this.dispatchExecutor = Executors.newFixedThreadPool(dispatchConcurrency,
                Thread.ofPlatform().name("job-dispatch-", 0).factory());
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

    /**
     * Executa já o job informado, se ele estiver pronto ({@code PENDING}, {@code run_at <= now()}) e houver vaga no
     * executor da execução imediata. A reivindicação e a execução correm na thread desse executor, nunca na de quem
     * chamou. Sem vaga, executor encerrado ou job já reivindicado → nada (o ciclo normal o executa).
     */
    @Override
    public void dispatch(UUID jobId) {
        if (!dispatchCapacity.tryAcquire()) {
            dispatched("busy");
            return;
        }
        // a correlação (requestId etc.) da thread que confirmou segue para os logs da execução imediata
        Runnable task = AsyncConfig.mdcPropagation().decorate(() -> {
            try {
                runDispatched(jobId);
            } finally {
                dispatchCapacity.release();
            }
        });
        try {
            dispatchExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            // instância em desligamento: o ciclo normal (desta ou de outra instância) executa o job
            dispatchCapacity.release();
            dispatched("rejected");
        }
    }

    private void runDispatched(UUID jobId) {
        Optional<ClaimedJob> job;
        try {
            job = claimOne(jobId);
        } catch (RuntimeException e) {
            // nada foi reivindicado (ex.: banco indisponível): o job segue PENDING para o ciclo normal
            dispatched("claim_error");
            LOG.warn("Falha ao reivindicar o job {} para execução imediata; fica para o ciclo normal: {}", jobId,
                    e.getClass().getName());
            return;
        }
        if (job.isEmpty()) {
            dispatched("missed");
            return;
        }
        dispatched("claimed");
        try {
            execute(job.get());
        } catch (RuntimeException e) {
            // o handler já rodou (ou falhou) e a baixa (nova tentativa/DEAD) não foi gravada: o job continua RUNNING
            // e volta pelo lease expirado, como numa queda da instância
            dispatched("finish_error");
            LOG.error("Falha ao registrar o resultado do job {} ({}) executado na hora; recuperado pelo lease", jobId,
                    job.get().type(), e);
        }
    }

    private void dispatched(String outcome) {
        meterRegistry.counter("jobs.dispatched", "outcome", outcome).increment();
    }

    /** Reivindica um job específico pelo id, com as mesmas condições e o mesmo efeito de {@link #claim}. */
    private Optional<ClaimedJob> claimOne(UUID jobId) {
        List<ClaimedJob> claimed = transaction.execute(status -> jdbcTemplate.query("""
                        update shared.processing_job j
                           set status = 'RUNNING', attempts = attempts + 1, locked_by = ?,
                               locked_until = now() + cast(? as interval), started_at = now()
                         where j.id in (select id from shared.processing_job
                                         where id = ? and status = 'PENDING' and run_at <= now()
                                         for update skip locked)
                        returning j.id, j.tenant_id, j.type, j.payload::text, j.attempts
                        """,
                (rs, i) -> new ClaimedJob(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getInt(5)),
                workerId, lease.toSeconds() + " seconds", jobId));
        return claimed == null || claimed.isEmpty() ? Optional.empty() : Optional.of(claimed.getFirst());
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
        running.put(job.id(), job.attempt());
        try {
            if (handler == null) {
                outcome = "dead";
                markDead(job, "Nenhum handler registrado para o tipo " + job.type());
                return;
            }
            if (job.attempt() > handler.maxAttempts()) {
                // reivindicado de novo depois de quedas da instância (lease expirado) além do limite
                outcome = "dead";
                LOG.error("Job {} ({}) movido para DEAD: {} tentativas interrompidas", job.id(), job.type(), job.attempt() - 1);
                markDead(job, "Tentativas esgotadas: instância interrompida durante a execução");
                return;
            }
            Object payload = json.fromJson(job.payload(), handler.payloadType());
            JobContext context = new JobContext(job.id(), job.tenantId(), job.type(), job.attempt());
            if (handler.transactional()) {
                tenantExecution.inTenant(job.tenantId(), "job:" + job.type(), () -> {
                    handler.handle(context, payload);
                    // fencing: só conclui quem ainda detém esta tentativa; senão desfaz o efeito do handler
                    if (!markDone(job)) {
                        throw new LeaseLostException();
                    }
                });
            } else {
                tenantExecution.withTenantContext(job.tenantId(), "job:" + job.type(), () -> handler.handle(context, payload));
                if (!Boolean.TRUE.equals(transaction.execute(s -> markDone(job)))) {
                    // sem transação externa não há o que desfazer: as etapas do handler já são idempotentes
                    throw new LeaseLostException();
                }
            }
        } catch (LeaseLostException e) {
            outcome = "lease_lost";
            LOG.warn("Job {} ({}) perdeu o lease na tentativa {}: outra instância o reivindicou", job.id(), job.type(),
                    job.attempt());
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
                        job.attempt(), backoffSeconds, e.getClass().getName());
                transaction.executeWithoutResult(s -> jdbcTemplate.update("""
                        update shared.processing_job
                           set status = 'PENDING', run_at = now() + cast(? as interval), locked_by = null,
                               locked_until = null, last_error = ?
                         where id = ? and status = 'RUNNING' and locked_by = ? and attempts = ?
                        """, backoffSeconds + " seconds", describe(e), job.id(), workerId, job.attempt()));
            }
        } finally {
            running.remove(job.id());
            sample.stop(meterRegistry.timer("jobs.duration", "type", job.type(), "outcome", outcome));
            meterRegistry.counter("jobs.executed", "type", job.type(), "outcome", outcome).increment();
            MDC.remove(MdcKeys.PROCESS);
        }
    }

    /** @return {@code false} se esta instância não detém mais a tentativa (outro worker reivindicou o job) */
    private boolean markDone(ClaimedJob job) {
        return jdbcTemplate.update("""
                update shared.processing_job set status = 'DONE', finished_at = now(), last_error = null
                 where id = ? and status = 'RUNNING' and locked_by = ? and attempts = ?
                """, job.id(), workerId, job.attempt()) == 1;
    }

    private void markDead(ClaimedJob job, String error) {
        transaction.executeWithoutResult(s -> jdbcTemplate.update("""
                update shared.processing_job
                   set status = 'DEAD', finished_at = now(), locked_by = null, locked_until = null, last_error = ?
                 where id = ? and status = 'RUNNING' and locked_by = ? and attempts = ?
                """, error, job.id(), workerId, job.attempt()));
    }

    /** Renova o lease dos jobs em execução nesta instância: job longo não volta para a fila enquanto roda. */
    @Scheduled(fixedDelayString = "${app.jobs.heartbeat-interval:PT1M}", initialDelayString = "${app.jobs.heartbeat-interval:PT1M}")
    void renewLeases() {
        if (running.isEmpty()) {
            return;
        }
        UUID[] ids = running.keySet().toArray(UUID[]::new);
        Integer renewed = transaction.execute(s -> jdbcTemplate.update(con -> {
            var statement = con.prepareStatement("""
                    update shared.processing_job set locked_until = now() + cast(? as interval)
                     where id = any(?) and status = 'RUNNING' and locked_by = ?
                    """);
            statement.setString(1, lease.toSeconds() + " seconds");
            statement.setArray(2, con.createArrayOf("uuid", ids));
            statement.setString(3, workerId);
            return statement;
        }));
        if (renewed != null && renewed > 0) {
            meterRegistry.counter("jobs.lease_renewed").increment(renewed);
        }
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

    /**
     * Motivo gravado em {@code last_error} (tabela sem RLS, lida por todos os tenants, DEAD guardado sem prazo):
     * código estável para erro de domínio; para os demais, a mensagem mascarada (decisão 853). O detalhe completo
     * fica no log técnico, que já passa pelo mascaramento.
     */
    static String describe(RuntimeException e) {
        String detail = e instanceof DomainException domain ? domain.code()
                : SensitiveDataMasker.mask(String.valueOf(e.getMessage()));
        String message = e.getClass().getName() + ": " + detail;
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    /** A tentativa deixou de ser desta instância (lease expirado e job reivindicado por outra). */
    private static final class LeaseLostException extends RuntimeException {
        LeaseLostException() {
            super("lease perdido", null, false, false);
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        // os dois executores param juntos, com um prazo único de 30 s
        dispatchExecutor.shutdown();
        executor.shutdown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        boolean dispatchDone = dispatchExecutor.awaitTermination(Math.max(0, deadline - System.nanoTime()),
                TimeUnit.NANOSECONDS);
        boolean workersDone = executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        if (!dispatchDone || !workersDone) {
            LOG.warn("Jobs ainda em execução no desligamento; serão recuperados pelo lease ({} workers)", concurrency);
            dispatchExecutor.shutdownNow();
            executor.shutdownNow();
        }
    }

    private record ClaimedJob(UUID id, UUID tenantId, String type, String payload, int attempt) {
    }
}
