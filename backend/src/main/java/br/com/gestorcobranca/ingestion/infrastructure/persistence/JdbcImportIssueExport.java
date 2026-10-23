package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.application.port.out.ImportIssueExport;
import br.com.gestorcobranca.ingestion.application.port.out.IssueLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Leitura das ocorrências por cursor (fetch size): o PostgreSQL entrega as linhas aos poucos
 * dentro da transação, e cada uma é repassada ao consumidor sem acumular em memória.
 */
@Component
class JdbcImportIssueExport implements ImportIssueExport {

    private static final int FETCH_SIZE = 1000;

    private final JdbcTemplate jdbcTemplate;

    JdbcImportIssueExport(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setFetchSize(FETCH_SIZE);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void forEachIssue(UUID batchId, Consumer<IssueLine> consumer) {
        jdbcTemplate.query("""
                        select row_number, severity, field, code, message, value_excerpt
                        from ingestion.import_row_issue
                        where batch_id = ?
                        order by row_number, severity, id
                        """,
                rs -> {
                    consumer.accept(new IssueLine(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6)));
                }, batchId);
    }
}
