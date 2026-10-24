package br.com.gestorcobranca.scoring.infrastructure.persistence;

import br.com.gestorcobranca.scoring.application.port.out.ScoringLocks;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.UUID;

/** {@code pg_advisory_xact_lock} com chave derivada de (tenant, escopo); liberados no fim da transação. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class PostgresScoringLocks implements ScoringLocks {

    private final NamedParameterJdbcTemplate jdbc;

    PostgresScoringLocks(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockTenant(String scope) {
        lock("scoring." + scope);
    }

    @Override
    public void lockCases(Collection<UUID> caseIds) {
        caseIds.stream().distinct().sorted().forEach(id -> lock("scoring.case:" + id));
    }

    private void lock(String key) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(shared.current_tenant_id()::text || ':' || :key, 0))",
                new MapSqlParameterSource("key", key));
    }
}
