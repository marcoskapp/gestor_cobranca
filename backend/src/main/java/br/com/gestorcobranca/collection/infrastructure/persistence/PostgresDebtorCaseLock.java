package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.application.port.out.DebtorCaseLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * {@link DebtorCaseLock} com {@code pg_advisory_xact_lock}: chave de 64 bits derivada por
 * {@code hashtextextended} de um texto com prefixo do módulo (sem colisão com os locks de outros
 * módulos), liberada no commit/rollback. Não exige linha de controle por devedor — a primeira
 * abertura de caso também precisa ser serializada.
 */
@Component
class PostgresDebtorCaseLock implements DebtorCaseLock {

    private final JdbcTemplate jdbcTemplate;

    PostgresDebtorCaseLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID tenantId, UUID debtorId) {
        jdbcTemplate.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> null, "collection.case:" + tenantId + ":" + debtorId);
    }
}
