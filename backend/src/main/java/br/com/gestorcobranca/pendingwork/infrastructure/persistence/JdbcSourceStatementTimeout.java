package br.com.gestorcobranca.pendingwork.infrastructure.persistence;

import br.com.gestorcobranca.pendingwork.application.port.out.SourceStatementTimeout;
import br.com.gestorcobranca.shared.persistence.StatementTimeout;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** {@code statement_timeout} local à transação (desfeito no fim dela; a conexão volta limpa ao pool). */
@Component
class JdbcSourceStatementTimeout implements SourceStatementTimeout {

    private final StatementTimeout statementTimeout;

    JdbcSourceStatementTimeout(StatementTimeout statementTimeout) {
        this.statementTimeout = statementTimeout;
    }

    @Override
    public void apply(Duration timeout) {
        statementTimeout.apply(timeout);
    }
}
