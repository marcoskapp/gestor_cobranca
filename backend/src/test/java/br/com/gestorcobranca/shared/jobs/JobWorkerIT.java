package br.com.gestorcobranca.shared.jobs;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.MeterRegistry;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Posse do job no {@link JobWorker} (decisão 852): handler sem transação, renovação do lease, fencing quando
 * outra instância reivindica o job e teto de tentativas para quedas sucessivas. E o {@code last_error} sem dado
 * pessoal (decisão 853). Execução imediata depois do commit ({@link JobQueue#enqueueAndDispatch}): roda sem o ciclo
 * do worker, nada roda com a transação desfeita, uma só execução com reivindicações concorrentes e, sem vaga na
 * execução imediata, o ciclo normal executa o job.
 */
@Import(JobWorkerIT.Handlers.class)
class JobWorkerIT extends IntegrationTest {

    static final AtomicBoolean RAN_IN_TRANSACTION = new AtomicBoolean(true);
    static final AtomicReference<Timestamp[]> LEASES = new AtomicReference<>();
    static final AtomicBoolean HANDLED = new AtomicBoolean(false);

    @Autowired
    JobQueue jobQueue;
    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    org.springframework.context.ApplicationContext applicationContext;

    private final UUID tenant = Ids.newId();

    @Test
    void nonTransactionalHandlerRunsOutsideTransactionAndJobIsDone() {
        UUID id = enqueue(NonTransactional.TYPE);
        JobDrain.drain(applicationContext, jdbc, NonTransactional.TYPE);
        assertThat(RAN_IN_TRANSACTION).isFalse();
        assertThat(status(id)).isEqualTo("DONE");
    }

    @Test
    void runningJobHasItsLeaseRenewed() {
        UUID id = enqueue(Renewing.TYPE);
        JobDrain.drain(applicationContext, jdbc, Renewing.TYPE);
        assertThat(status(id)).isEqualTo("DONE");
        Timestamp[] leases = LEASES.get();
        assertThat(leases[1]).isAfter(leases[0]);
    }

    @Test
    void executionThatLostTheLeaseIsRolledBackAndDoesNotFinishTheJob() throws InterruptedException {
        UUID id = enqueue(Stolen.TYPE);
        JobDrain.drainUntil(applicationContext, HANDLED::get);
        Thread.sleep(300); // a baixa (recusada) acontece logo depois do handler
        // outra instância detém o job: esta execução não o conclui e o efeito do handler foi desfeito
        Map<String, Object> row = jdbc.queryForMap("select status, locked_by from shared.processing_job where id = ?", id);
        assertThat(row).containsEntry("status", "RUNNING").containsEntry("locked_by", "outra-instancia");
        assertThat(jdbc.queryForObject("select count(*) from shared.processing_job where type = 'test.stolen.effect'",
                Long.class)).isZero();
    }

    @Test
    void jobReclaimedBeyondMaxAttemptsGoesDeadWithoutRunning() {
        UUID id = enqueue(NonTransactional.TYPE);
        jdbc.update("update shared.processing_job set attempts = 3 where id = ?", id); // maxAttempts = 3
        RAN_IN_TRANSACTION.set(true);
        JobDrain.drain(applicationContext, jdbc, NonTransactional.TYPE);
        assertThat(status(id)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("select last_error from shared.processing_job where id = ?", String.class, id))
                .contains("Tentativas esgotadas");
    }

    @Test
    void lastErrorKeepsDomainCodeAndMasksPersonalData() {
        assertThat(JobWorker.describe(new BusinessRuleException("debtor.invalid", "CPF 123.456.789-09 inválido")))
                .endsWith(": debtor.invalid").doesNotContain("123.456.789-09");
        assertThat(JobWorker.describe(new IllegalStateException("Key (document)=(12345678909) já existe")))
                .doesNotContain("12345678909");
    }

    @Test
    void dispatchedJobRunsRightAfterCommitWithoutTheWorkerCycle() {
        String key = Ids.newId().toString();
        UUID id = tenantExecution.callInTenant(tenant, "test", () -> {
            UUID created = jobQueue.enqueueAndDispatch(Command.TYPE, new Key(key), null);
            // antes do commit nada é executado
            assertThat(Command.EXECUTIONS.get(key)).isNull();
            return created;
        });
        // o ciclo do worker não roda (agendamento desligado nos testes): só a execução imediata conclui o job
        await(() -> "DONE".equals(status(id)));
        assertThat(Command.EXECUTIONS.get(key)).hasValue(1);
        assertThat(attempts(id)).isEqualTo(1);
    }

    @Test
    void rolledBackTransactionNeitherKeepsNorRunsTheJob() {
        String key = Ids.newId().toString();
        assertThatThrownBy(() -> tenantExecution.inTenant(tenant, "test", () -> {
            jobQueue.enqueueAndDispatch(Command.TYPE, new Key(key), null);
            throw new IllegalStateException("desfaz a operação");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from shared.processing_job where payload ->> 'key' = ?",
                Long.class, key)).isZero();
        assertThat(Command.EXECUTIONS.get(key)).isNull();
    }

    @Test
    void concurrentDispatchesAndWorkerCyclesRunTheJobOnce() throws Exception {
        String key = Ids.newId().toString();
        // job confirmado e ainda não executado (ex.: o pedido imediato não chegou a ser feito)
        UUID id = tenantExecution.callInTenant(tenant, "test", () -> jobQueue.enqueue(Command.TYPE, new Key(key), null, null));
        JobWorker worker = applicationContext.getBean(JobWorker.class);
        int contenders = 6;
        CyclicBarrier start = new CyclicBarrier(contenders);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            boolean dispatch = i % 2 == 0;
            Thread thread = new Thread(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                if (dispatch) {
                    worker.dispatch(id);
                } else {
                    worker.poll();
                }
            });
            thread.start();
            threads.add(thread);
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        await(() -> "DONE".equals(status(id)));
        assertThat(Command.EXECUTIONS.get(key)).hasValue(1);
        assertThat(attempts(id)).isEqualTo(1);
    }

    @Test
    void jobWithoutRoomForImmediateExecutionRunsInTheWorkerCycle() {
        // ocupa todas as vagas da execução imediata
        Blocking.RELEASE.set(new CountDownLatch(1));
        List<UUID> blocking = new ArrayList<>();
        try {
            for (int i = 0; i < dispatchConcurrency; i++) {
                blocking.add(dispatchNow(Blocking.TYPE, Ids.newId().toString()));
            }
            await(() -> blocking.stream().allMatch(b -> "RUNNING".equals(status(b))));
            double busyBefore = meterRegistry.counter("jobs.dispatched", "outcome", "busy").count();
            String key = Ids.newId().toString();
            UUID id = dispatchNow(Command.TYPE, key);
            assertThat(meterRegistry.counter("jobs.dispatched", "outcome", "busy").count()).isEqualTo(busyBefore + 1);
            assertThat(status(id)).isEqualTo("PENDING");

            JobDrain.drain(applicationContext, jdbc, Command.TYPE);
            assertThat(status(id)).isEqualTo("DONE");
            assertThat(Command.EXECUTIONS.get(key)).hasValue(1);
        } finally {
            Blocking.RELEASE.get().countDown();
        }
        await(() -> blocking.stream().allMatch(b -> "DONE".equals(status(b))));
    }

    @Autowired
    MeterRegistry meterRegistry;
    @Value("${app.jobs.dispatch-concurrency}")
    int dispatchConcurrency;

    private UUID dispatchNow(String type, String key) {
        return tenantExecution.callInTenant(tenant, "test", () -> jobQueue.enqueueAndDispatch(type, new Key(key), null));
    }

    private int attempts(UUID id) {
        Integer value = jdbc.queryForObject("select attempts from shared.processing_job where id = ?", Integer.class, id);
        return value == null ? 0 : value;
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condição não atingida a tempo");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrompido");
            }
        }
    }

    private UUID enqueue(String type) {
        return tenantExecution.callInTenant(tenant, "test", () -> jobQueue.enqueue(type, Map.of(), null, null));
    }

    private String status(UUID id) {
        return jdbc.queryForObject("select status from shared.processing_job where id = ?", String.class, id);
    }


    @TestConfiguration
    static class Handlers {

        @Bean
        NonTransactional nonTransactionalTestHandler() {
            return new NonTransactional();
        }

        @Bean
        Command commandTestHandler() {
            return new Command();
        }

        @Bean
        Blocking blockingTestHandler() {
            return new Blocking();
        }

        @Bean
        Renewing renewingTestHandler(JdbcTemplate jdbc, org.springframework.context.ApplicationContext context) {
            return new Renewing(jdbc, context);
        }

        @Bean
        Stolen stolenTestHandler(JdbcTemplate jdbc, JobQueue queue) {
            return new Stolen(jdbc, queue);
        }
    }

    record Empty() {
    }

    record Key(String key) {
    }

    /** Comando curto sem transação (como os da telefonia): conta as execuções por chave. */
    static final class Command implements JobHandler<Key> {
        static final String TYPE = "test.command";
        static final Map<String, AtomicInteger> EXECUTIONS = new ConcurrentHashMap<>();

        public String type() {
            return TYPE;
        }

        public Class<Key> payloadType() {
            return Key.class;
        }

        public void handle(JobContext context, Key payload) {
            EXECUTIONS.computeIfAbsent(payload.key(), k -> new AtomicInteger()).incrementAndGet();
            try {
                Thread.sleep(200); // alarga a janela em que outra reivindicação poderia pegar o mesmo job
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean transactional() {
            return false;
        }
    }

    /** Ocupa uma vaga de execução até ser liberado. */
    static final class Blocking implements JobHandler<Key> {
        static final String TYPE = "test.blocking";
        static final AtomicReference<CountDownLatch> RELEASE = new AtomicReference<>(new CountDownLatch(0));

        public String type() {
            return TYPE;
        }

        public Class<Key> payloadType() {
            return Key.class;
        }

        public void handle(JobContext context, Key payload) {
            try {
                RELEASE.get().await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean transactional() {
            return false;
        }
    }

    static final class NonTransactional implements JobHandler<Empty> {
        static final String TYPE = "test.non-transactional";

        public String type() {
            return TYPE;
        }

        public Class<Empty> payloadType() {
            return Empty.class;
        }

        public void handle(JobContext context, Empty payload) {
            RAN_IN_TRANSACTION.set(TransactionSynchronizationManager.isActualTransactionActive());
        }

        @Override
        public boolean transactional() {
            return false;
        }

        @Override
        public int maxAttempts() {
            return 3;
        }
    }

    static final class Renewing implements JobHandler<Empty> {
        static final String TYPE = "test.renewing";
        private final JdbcTemplate jdbc;
        private final org.springframework.context.ApplicationContext context;

        Renewing(JdbcTemplate jdbc, org.springframework.context.ApplicationContext context) {
            this.jdbc = jdbc;
            this.context = context;
        }

        public String type() {
            return TYPE;
        }

        public Class<Empty> payloadType() {
            return Empty.class;
        }

        public void handle(JobContext context, Empty payload) {
            String sql = "select locked_until from shared.processing_job where id = ?";
            Timestamp before = jdbc.queryForObject(sql, Timestamp.class, context.jobId());
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.context.getBean(JobWorker.class).renewLeases();
            LEASES.set(new Timestamp[]{before, jdbc.queryForObject(sql, Timestamp.class, context.jobId())});
        }

        @Override
        public boolean transactional() {
            return false;
        }
    }

    static final class Stolen implements JobHandler<Empty> {
        static final String TYPE = "test.stolen";
        private final JdbcTemplate jdbc;
        private final JobQueue queue;

        Stolen(JdbcTemplate jdbc, JobQueue queue) {
            this.jdbc = jdbc;
            this.queue = queue;
        }

        public String type() {
            return TYPE;
        }

        public Class<Empty> payloadType() {
            return Empty.class;
        }

        public void handle(JobContext context, Empty payload) {
            // efeito do handler na transação do job (deve ser desfeito) …
            queue.enqueue("test.stolen.effect", Map.of(), java.time.Instant.now().plusSeconds(3600), null);
            // … e, noutra conexão, o lease expira e outra instância reivindica o job
            new Thread(() -> jdbc.update("""
                    update shared.processing_job set locked_by = 'outra-instancia', attempts = attempts + 1 where id = ?
                    """, context.jobId())).start();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            HANDLED.set(true);
        }
    }
}
