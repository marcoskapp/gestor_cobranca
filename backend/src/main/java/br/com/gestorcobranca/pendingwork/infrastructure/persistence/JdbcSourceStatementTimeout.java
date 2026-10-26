package br.com.gestorcobranca.pendingwork.infrastructure.persistence;

import br.com.gestorcobranca.pendingwork.application.port.out.SourceStatementTimeout;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** {@code statement_timeout} local à transação (desfeito no fim dela; a conexão volta limpa ao pool). */
@Component
class JdbcSourceStatementTimeout implements SourceStatementTimeout {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcSourceStatementTimeout(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void apply(Duration timeout) {
        jdbc.queryForObject("select set_config('statement_timeout', :timeout, true)",
                new MapSqlParameterSource("timeout", Long.toString(Math.max(1, timeout.toMillis()))), String.class);
    }
}
