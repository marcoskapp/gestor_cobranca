package br.com.gestorcobranca.payment.infrastructure.persistence;

import br.com.gestorcobranca.payment.application.port.out.ExternalPaymentLocks;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** {@code pg_advisory_xact_lock} com chaves no namespace do módulo (por tenant). */
@Component
class PostgresExternalPaymentLocks implements ExternalPaymentLocks {

    private final JdbcTemplate jdbc;

    PostgresExternalPaymentLocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockExternalRegistration(UUID tenantId, UUID informedBy, UUID creditorId, UUID debtorId) {
        lock("payment.external.informer:" + tenantId + ":" + informedBy);
        lock("payment.external.debtor:" + tenantId + ":" + creditorId + ":" + debtorId);
    }

    private void lock(String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, key);
    }
}
