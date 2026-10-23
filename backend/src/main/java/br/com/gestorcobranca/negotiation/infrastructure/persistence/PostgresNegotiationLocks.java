package br.com.gestorcobranca.negotiation.infrastructure.persistence;

import br.com.gestorcobranca.negotiation.application.port.out.NegotiationLocks;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** {@link NegotiationLocks} com {@code pg_advisory_xact_lock} (chave de 64 bits por tenant e devedor). */
@Component
class PostgresNegotiationLocks implements NegotiationLocks {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentActor currentActor;

    PostgresNegotiationLocks(JdbcTemplate jdbcTemplate, CurrentActor currentActor) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentActor = currentActor;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockDebtor(UUID debtorId) {
        jdbcTemplate.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null,
                "negotiation.debtor:" + currentActor.tenantId() + ":" + debtorId);
    }
}
