package br.com.gestorcobranca.shared.jobs;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Posse do job no {@link JobWorker} (decisão 852): handler sem transação, renovação do lease, fencing quando
 * outra instância reivindica o job e teto de tentativas para quedas sucessivas. E o {@code last_error} sem dado
 * pessoal (decisão 853).
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
