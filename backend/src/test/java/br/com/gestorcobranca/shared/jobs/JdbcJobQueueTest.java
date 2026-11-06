package br.com.gestorcobranca.shared.jobs;

import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.security.CurrentActor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JobQueue#enqueueAndDispatch}: o job é gravado na transação corrente e a execução imediata só é pedida
 * depois do commit — nunca antes, nunca com a transação desfeita, nunca sem transação; sem worker disponível
 * (desligamento) a falha é contada e não chega a quem confirmou.
 */
class JdbcJobQueueTest {

    private final UUID tenant = Ids.newId();
    private final List<String> statements = new CopyOnWriteArrayList<>();
    private final List<UUID> dispatched = new CopyOnWriteArrayList<>();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void dispatchesTheJobOnlyAfterCommit() {
        JdbcJobQueue queue = queue(withWorker());
        TransactionSynchronizationManager.initSynchronization();
        UUID id = queue.enqueueAndDispatch("test.cmd", Map.of("callId", "x"), null);

        assertThat(statements).anySatisfy(sql -> assertThat(sql).contains("insert into shared.processing_job"));
        assertThat(dispatched).isEmpty();
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(s -> s.beforeCommit(false));
        assertThat(dispatched).isEmpty();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        assertThat(dispatched).containsExactly(id);
    }

    @Test
    void rolledBackTransactionDispatchesNothing() {
        JdbcJobQueue queue = queue(withWorker());
        TransactionSynchronizationManager.initSynchronization();
        queue.enqueueAndDispatch("test.cmd", Map.of(), null);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(dispatched).isEmpty();
    }

    @Test
    void refusesToEnqueueWithoutTransaction() {
        JdbcJobQueue queue = queue(withWorker());
        assertThatThrownBy(() -> queue.enqueueAndDispatch("test.cmd", Map.of(), null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(statements).isEmpty();
        assertThat(dispatched).isEmpty();
    }

    @Test
    void unavailableWorkerAfterCommitIsCountedAndDoesNotReachTheCommitter() {
        // contexto em desligamento: nenhum JobDispatcher a obter
        JdbcJobQueue queue = queue(new StaticListableBeanFactory());
        TransactionSynchronizationManager.initSynchronization();
        queue.enqueueAndDispatch("test.cmd", Map.of(), null);

        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
        assertThat(meterRegistry.counter("jobs.dispatched", "outcome", "rejected").count()).isEqualTo(1);
    }

    private StaticListableBeanFactory withWorker() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("dispatcher", (JobDispatcher) dispatched::add);
        return beans;
    }

    private JdbcJobQueue queue(StaticListableBeanFactory beans) {
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public int update(String sql, Object... args) {
                statements.add(sql);
                return 1;
            }
        };
        CurrentActor actor = new CurrentActor() {
            @Override
            public UUID tenantId() {
                return tenant;
            }

            @Override
            public UUID actorId() {
                return null;
            }
        };
        return new JdbcJobQueue(jdbc, actor, new JsonCodec(JsonMapper.builder().build()),
                beans.getBeanProvider(JobDispatcher.class), meterRegistry);
    }
}
