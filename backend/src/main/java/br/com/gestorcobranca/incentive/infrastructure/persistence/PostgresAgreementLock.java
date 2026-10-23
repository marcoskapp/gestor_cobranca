package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.application.port.out.AgreementLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Advisory lock transacional por acordo (namespace próprio do módulo), liberado no commit ou
 * rollback.
 */
@Component
class PostgresAgreementLock implements AgreementLock {

    private static final int NAMESPACE = 0x1800;

    private final JdbcTemplate jdbcTemplate;

    PostgresAgreementLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID agreementId) {
        jdbcTemplate.query("select pg_advisory_xact_lock(?, ?)", rs -> null, NAMESPACE,
                (int) (agreementId.getMostSignificantBits() ^ agreementId.getLeastSignificantBits()));
    }
}
