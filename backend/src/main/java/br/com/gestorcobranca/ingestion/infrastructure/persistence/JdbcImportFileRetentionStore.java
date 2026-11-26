package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.application.port.out.ImportFileRetentionStore;
import br.com.gestorcobranca.shared.domain.Ids;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
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

    /** Espelha {@code PdfImportStatus.open()}. */
    private static final String PDF_OPEN = "('RECEIVED', 'MAPPING', 'AWAITING_MAP', 'AWAITING_VALIDATION')";

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
        // trava os lotes da chave em ordem de id (a mesma do anonimizador, que os trava numa instrução): sem ciclo
        return jdbc.update("""
                with locked as (
                    select b.id from ingestion.import_batch b
                     where b.tenant_id = shared.current_tenant_id() and b.file_key = :key
                     order by b.id
                       for update)
                update ingestion.import_batch u
                   set file_key = null, file_deleted_at = :at, updated_at = :at, version = u.version + 1
                  from locked l
                 where u.id = l.id and u.tenant_id = shared.current_tenant_id()
                   and not exists (select 1 from ingestion.import_batch y
                                    where y.tenant_id = shared.current_tenant_id() and y.file_key = :key
                                      and y.status not in %s)
                   and not %s
                """.formatted(TERMINAL, OPEN_REVIEW.formatted(":key")),
                new MapSqlParameterSource("key", fileKey).addValue("at", Timestamp.from(at)));
    }

    @Override
    public List<ExpiredFile> filesWithDebtor(UUID debtorId) {
        return jdbc.query("""
                select b.file_key,
                       (array_agg(b.id order by b.created_at, b.id))[1] as batch_id,
                       (array_agg(b.creditor_id order by b.created_at, b.id))[1] as creditor_id,
                       (array_agg(b.file_sha256 order by b.created_at, b.id))[1] as file_sha256,
                       count(*) as batches
                  from ingestion.import_batch b
                 where b.tenant_id = shared.current_tenant_id() and b.file_key is not null
                   and b.file_key in (select x.file_key from ingestion.import_batch x
                                       where x.tenant_id = shared.current_tenant_id() and x.file_key is not null
                                         and x.id in (select r.batch_id from ingestion.import_batch_row r
                                                       where r.tenant_id = shared.current_tenant_id()
                                                         and r.debtor_id = :debtorId))
                 group by b.file_key
                """, new MapSqlParameterSource("debtorId", debtorId),
                (rs, i) -> new ExpiredFile(rs.getString("file_key"), rs.getObject("batch_id", UUID.class),
                        rs.getObject("creditor_id", UUID.class), rs.getString("file_sha256"), rs.getInt("batches")));
    }

    private static final org.springframework.jdbc.core.RowMapper<SubjectRow> SUBJECT_ROW = (rs, i) -> new SubjectRow(
            rs.getObject("batch_id", UUID.class), rs.getObject("creditor_id", UUID.class), rs.getLong("row_number"),
            rs.getString("external_id"), rs.getBoolean("by_debtor"));

    /** Índice: {@code ix_import_batch_row_by_debtor} (V2304). */
    @Override
    public List<SubjectRow> rowsOfDebtor(UUID debtorId) {
        return jdbc.query("""
                select r.batch_id, b.creditor_id, r.row_number, r.external_id, true as by_debtor
                  from ingestion.import_batch_row r
                  join ingestion.import_batch b on b.tenant_id = r.tenant_id and b.id = r.batch_id
                 where r.tenant_id = shared.current_tenant_id() and r.debtor_id = :debtorId
                 order by r.batch_id, r.row_number
                """, new MapSqlParameterSource("debtorId", debtorId), SUBJECT_ROW);
    }

    /** Índice: {@code ix_import_batch_row_by_external_id} (V2305). */
    @Override
    public List<SubjectRow> rowsOfTitles(UUID creditorId, Collection<String> externalIds) {
        if (externalIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                select r.batch_id, b.creditor_id, r.row_number, r.external_id, false as by_debtor
                  from ingestion.import_batch_row r
                  join ingestion.import_batch b on b.tenant_id = r.tenant_id and b.id = r.batch_id
                 where r.tenant_id = shared.current_tenant_id() and r.external_id in (:ids)
                   and b.creditor_id = :creditorId
                 order by r.batch_id, r.row_number
                """, new MapSqlParameterSource("ids", externalIds).addValue("creditorId", creditorId), SUBJECT_ROW);
    }

    @Override
    public List<UUID> batchesWithFileKey(String fileKey) {
        return jdbc.queryForList("""
                select b.id from ingestion.import_batch b
                 where b.tenant_id = shared.current_tenant_id() and b.file_key = :key
                 order by b.id
                """, new MapSqlParameterSource("key", fileKey), UUID.class);
    }

    @Override
    public List<BatchFile> filesOfBatches(Collection<UUID> batchIds) {
        if (batchIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                select b.id, b.creditor_id, b.file_key, b.file_sha256 from ingestion.import_batch b
                 where b.tenant_id = shared.current_tenant_id() and b.id in (:ids)
                """, new MapSqlParameterSource("ids", batchIds),
                (rs, i) -> new BatchFile(rs.getObject("id", UUID.class), rs.getObject("creditor_id", UUID.class),
                        rs.getString("file_key"), rs.getString("file_sha256")));
    }

    @Override
    public List<UUID> batchesWithContent(UUID creditorId, String fileSha256) {
        return jdbc.queryForList("""
                select b.id from ingestion.import_batch b
                 where b.tenant_id = shared.current_tenant_id() and b.creditor_id = :creditorId
                   and b.file_sha256 = :sha
                 order by b.id
                """, new MapSqlParameterSource("creditorId", creditorId).addValue("sha", fileSha256), UUID.class);
    }

    @Override
    public List<UUID> pdfImportsOfBatches(Collection<UUID> batchIds) {
        if (batchIds.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("""
                select distinct p.id from ingestion.pdf_import p
                  join ingestion.import_batch b on b.tenant_id = p.tenant_id and b.review_id = p.review_id
                 where p.tenant_id = shared.current_tenant_id() and p.file_key is not null and b.id in (:ids)
                """, new MapSqlParameterSource("ids", batchIds), UUID.class);
    }

    @Override
    public List<UUID> pdfImportsWithDebtor(UUID debtorId) {
        return jdbc.queryForList("""
                select distinct p.id from ingestion.pdf_import p
                  join ingestion.import_batch b on b.tenant_id = p.tenant_id and b.review_id = p.review_id
                 where p.tenant_id = shared.current_tenant_id() and p.file_key is not null
                   and b.id in (select r.batch_id from ingestion.import_batch_row r
                                 where r.tenant_id = shared.current_tenant_id() and r.debtor_id = :debtorId)
                """, new MapSqlParameterSource("debtorId", debtorId), UUID.class);
    }

    @Override
    public boolean addPendingErasure(String fileKey, UUID pdfImportId, UUID sourceEventId, Instant at) {
        return jdbc.update("""
                insert into ingestion.pending_file_erasure (id, tenant_id, file_key, pdf_import_id, source_event_id, created_at)
                values (:id, shared.current_tenant_id(), :key, :pdf, :event, :at)
                on conflict do nothing
                """, new MapSqlParameterSource("id", Ids.newId()).addValue("key", fileKey)
                .addValue("pdf", pdfImportId).addValue("event", sourceEventId).addValue("at", Timestamp.from(at))) == 1;
    }

    @Override
    public List<PendingErasure> pendingErasures(int limit) {
        return jdbc.query("""
                select e.id, e.file_key, e.pdf_import_id, e.source_event_id
                  from ingestion.pending_file_erasure e
                 where e.tenant_id = shared.current_tenant_id()
                   and ((e.pdf_import_id is not null
                         and not exists (select 1 from ingestion.pdf_import p
                                          where p.tenant_id = shared.current_tenant_id() and p.id = e.pdf_import_id
                                            and p.file_key is not null and p.status in %s))
                     or (e.file_key is not null
                         and not exists (select 1 from ingestion.import_batch y
                                          where y.tenant_id = shared.current_tenant_id() and y.file_key = e.file_key
                                            and y.status not in %s)
                         and not %s))
                 order by e.created_at, e.id
                 limit :limit
                """.formatted(PDF_OPEN, TERMINAL, OPEN_REVIEW.formatted("e.file_key")),
                new MapSqlParameterSource("limit", limit),
                (rs, i) -> new PendingErasure(rs.getObject("id", UUID.class), rs.getString("file_key"),
                        rs.getObject("pdf_import_id", UUID.class), rs.getObject("source_event_id", UUID.class)));
    }

    @Override
    public void removePendingErasure(UUID id) {
        jdbc.update("delete from ingestion.pending_file_erasure where tenant_id = shared.current_tenant_id() and id = :id",
                new MapSqlParameterSource("id", id));
    }

    @Override
    public List<ExpiredFile> fileByKey(String fileKey) {
        return jdbc.query("""
                select b.file_key,
                       (array_agg(b.id order by b.created_at, b.id))[1] as batch_id,
                       (array_agg(b.creditor_id order by b.created_at, b.id))[1] as creditor_id,
                       (array_agg(b.file_sha256 order by b.created_at, b.id))[1] as file_sha256,
                       count(*) as batches
                  from ingestion.import_batch b
                 where b.tenant_id = shared.current_tenant_id() and b.file_key = :key
                 group by b.file_key
                """, new MapSqlParameterSource("key", fileKey),
                (rs, i) -> new ExpiredFile(rs.getString("file_key"), rs.getObject("batch_id", UUID.class),
                        rs.getObject("creditor_id", UUID.class), rs.getString("file_sha256"), rs.getInt("batches")));
    }
}
