package br.com.gestorcobranca.shared.persistence;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

/**
 * {@code statement_timeout} local à transação corrente ({@code set_config(..., true)}): vale para as consultas seguintes
 * da mesma transação e é desfeito no fim dela, então a conexão volta limpa ao pool. Precisa ser chamado dentro de uma
 * transação; o valor vai em milissegundos, no mínimo 1 (zero, no PostgreSQL, desligaria o limite).
 */
@Component
public class StatementTimeout {

    private final NamedParameterJdbcTemplate jdbc;

    StatementTimeout(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void apply(Duration timeout) {
        // fora de transação o set_config local não vale para nada: falha alto em vez de seguir sem limite
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("statement_timeout local exige transação ativa");
        }
        jdbc.queryForObject("select set_config('statement_timeout', :timeout, true)",
                new MapSqlParameterSource("timeout", Long.toString(Math.max(1, timeout.toMillis()))), String.class);
    }
}
