package br.com.gestorcobranca.settlement.infrastructure.persistence;

import br.com.gestorcobranca.settlement.application.port.out.SettlementLocks;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** {@code pg_advisory_xact_lock} com chave derivada do id (namespace do módulo para não colidir com outros). */
@Component
class PostgresSettlementLocks implements SettlementLocks {

    private final JdbcTemplate jdbc;

    PostgresSettlementLocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAllocation(UUID allocationId) {
        lock("settlement.allocation:" + allocationId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAgreement(UUID agreementId) {
        lock("settlement.agreement:" + agreementId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockExpense(UUID legalExpenseId) {
        lock("settlement.expense:" + legalExpenseId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockCreditor(UUID creditorId) {
        lock("settlement.creditor:" + creditorId);
    }

    private void lock(String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, key);
    }
}
