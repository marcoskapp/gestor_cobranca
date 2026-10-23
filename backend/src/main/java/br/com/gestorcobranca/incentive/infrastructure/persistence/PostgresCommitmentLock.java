package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.application.port.out.CommitmentLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Advisory locks transacionais por retorno e por promessa (namespaces próprios do módulo, distintos
 * do lock por acordo), liberados no commit ou rollback.
 */
@Component
class PostgresCommitmentLock implements CommitmentLock {

    private static final int CALLBACK_NAMESPACE = 0x1801;
    private static final int PROMISE_NAMESPACE = 0x1802;

    private final JdbcTemplate jdbcTemplate;

    PostgresCommitmentLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockCallback(UUID taskId) {
        lock(CALLBACK_NAMESPACE, taskId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPromise(UUID promiseId) {
        lock(PROMISE_NAMESPACE, promiseId);
    }

    private void lock(int namespace, UUID id) {
        jdbcTemplate.query("select pg_advisory_xact_lock(?, ?)", rs -> null, namespace,
                (int) (id.getMostSignificantBits() ^ id.getLeastSignificantBits()));
    }
}
