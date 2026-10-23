package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.application.port.out.DebtorContactLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Lock transacional por (tenant, devedor) com {@code pg_advisory_xact_lock}. A chave de 64 bits
 * é derivada por {@code hashtextextended} de um texto com prefixo do módulo, evitando colisão
 * com outros usos de advisory lock; o lock é liberado automaticamente no commit/rollback.
 * <p>
 * Escolhido em vez de {@code SELECT ... FOR UPDATE} numa linha de controle porque não exige
 * criar nem manter uma linha por devedor (a primeira tentativa de um devedor também precisa
 * ser serializada) e não toca tabelas de outro módulo.
 */
@Component
class PostgresDebtorContactLock implements DebtorContactLock {

    private final JdbcTemplate jdbcTemplate;

    PostgresDebtorContactLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID tenantId, UUID debtorId) {
        jdbcTemplate.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> null, "compliance.contact_gate:" + tenantId + ":" + debtorId);
    }
}
