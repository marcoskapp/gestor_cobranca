package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.application.port.out.ImportFileRetentionStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Arquivos de importação vencidos em {@code ingestion.import_batch} (RLS por tenant; o filtro explícito pelo tenant
 * usa o índice parcial {@code ix_import_batch_file_retention}). Encerrados = {@code ImportBatchStatus.isTerminal()};
 * conferência aberta = {@code OPEN} ou {@code AWAITING_APPROVAL}.
 */
@Component
class JdbcImportFileRetentionStore implements ImportFileRetentionStore {

    private static final String TERMINAL = "('VALIDATED', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED')";
    private static final String OPEN_REVIEW = """
            exists (select 1 from ingestion.import_review r
                      join ingestion.import_batch x on x.id = r.current_batch_id
                     where x.tenant_id = shared.current_tenant_id() and x.file_key = %s
                       and r.status in ('OPEN', 'AWAITING_APPROVAL'))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcImportFileRetentionStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ExpiredFile> expiredFiles(Instant finishedBefore, int limit) {
        return jdbc.query("""
                select b.file_key,
                       (array_agg(b.id order by b.created_at, b.id))[1] as batch_id,
                       (array_agg(b.creditor_id order by b.created_at, b.id))[1] as creditor_id,
                       (array_agg(b.file_sha256 order by b.created_at, b.id))[1] as file_sha256,
                       count(*) as batches
                  from ingestion.import_batch b
                 where b.tenant_id = shared.current_tenant_id() and b.file_key is not null
                 group by b.file_key
                having bool_and(b.status in %s)
                   and max(coalesce(b.finished_at, b.updated_at)) < :before
                   and not %s
                 order by min(b.created_at)
                 limit :limit
                """.formatted(TERMINAL, OPEN_REVIEW.formatted("b.file_key")),
                new MapSqlParameterSource("before", Timestamp.from(finishedBefore)).addValue("limit", limit),
                (rs, i) -> new ExpiredFile(rs.getString("file_key"), rs.getObject("batch_id", UUID.class),
                        rs.getObject("creditor_id", UUID.class), rs.getString("file_sha256"), rs.getInt("batches")));
    }

    @Override
    public int markDeleted(String fileKey, Instant at) {
        return jdbc.update("""
                update ingestion.import_batch
                   set file_key = null, file_deleted_at = :at, updated_at = :at, version = version + 1
                 where tenant_id = shared.current_tenant_id() and file_key = :key
                   and not exists (select 1 from ingestion.import_batch y
                                    where y.tenant_id = shared.current_tenant_id() and y.file_key = :key
                                      and y.status not in %s)
                   and not %s
                """.formatted(TERMINAL, OPEN_REVIEW.formatted(":key")),
                new MapSqlParameterSource("key", fileKey).addValue("at", Timestamp.from(at)));
    }
}
