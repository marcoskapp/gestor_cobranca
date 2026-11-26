package br.com.gestorcobranca.ingestion.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Seleção e marcação dos arquivos de importação a descartar pela política {@code IMPORT_FILES} (tenant corrente).
 * Um arquivo pode ser compartilhado por vários lotes (prévia, importação e reprocessamento usam a mesma chave): só é
 * candidato quando <strong>todos</strong> os lotes com a chave estão encerrados antes do corte e nenhuma conferência
 * aberta usa um deles.
 */
public interface ImportFileRetentionStore {

    /**
     * @param batchId    lote mais antigo com a chave (recurso da auditoria)
     * @param creditorId credor dos lotes
     * @param batches    lotes que compartilham o arquivo
     */
    record ExpiredFile(String fileKey, UUID batchId, UUID creditorId, String fileSha256, int batches) {
    }

    List<ExpiredFile> expiredFiles(Instant finishedBefore, int limit);

    /**
     * Apaga a chave em todos os lotes que a usam, conferindo de novo (na mesma instrução) que continuam todos
     * encerrados e sem conferência aberta.
     *
     * @return lotes marcados (zero: a situação mudou e o arquivo fica)
     */
    int markDeleted(String fileKey, Instant at);

    /**
     * Arquivos ainda guardados de lotes com linha do devedor (anonimização do titular, SEG-20261124-D01): o arquivo
     * mistura vários devedores e sai inteiro (decisão do responsável de 24/11/2026).
     */
    List<ExpiredFile> filesWithDebtor(UUID debtorId);

    /** O arquivo ainda guardado com a chave (vazio se já saiu), com os dados do lote mais antigo. */
    List<ExpiredFile> fileByKey(String fileKey);

    /** PDFs ainda guardados das importações PDF cujos lotes têm linha do devedor. */
    List<UUID> pdfImportsWithDebtor(UUID debtorId);

    /**
     * Linha de lote com o titular anonimizado (SEG-20261124-D01h).
     *
     * @param externalId identificador do título como veio no arquivo — pode ter o CPF embutido: só para casar e
     *                   mascarar, nunca gravar nem registrar em log
     * @param byDebtor   a linha gravou o devedor (senão, casou pelo título do credor)
     */
    record SubjectRow(UUID batchId, UUID creditorId, long rowNumber, String externalId, boolean byDebtor) {

        @Override
        public String toString() {
            return "SubjectRow[batchId=" + batchId + ", rowNumber=" + rowNumber + ", byDebtor=" + byDebtor + "]";
        }
    }

    /** Linhas de lotes que gravaram o devedor, em ordem de lote e linha. */
    List<SubjectRow> rowsOfDebtor(UUID debtorId);

    /** Linhas de lotes do credor com estes identificadores de título (inclusive sem gravação: prévia, erro). */
    List<SubjectRow> rowsOfTitles(UUID creditorId, Collection<String> externalIds);

    /** Lotes que usam a chave do arquivo (prévia, importação, reprocessamento e nova conferência compartilham). */
    List<UUID> batchesWithFileKey(String fileKey);

    /** Chave e conteúdo do arquivo de cada lote (descoberta sem carregar a entidade antes da trava). */
    record BatchFile(UUID batchId, UUID creditorId, String fileKey, String fileSha256) {
    }

    List<BatchFile> filesOfBatches(Collection<UUID> batchIds);

    /** Lotes do credor com o mesmo conteúdo (o reenvio do mesmo arquivo grava outra chave). */
    List<UUID> batchesWithContent(UUID creditorId, String fileSha256);

    /** Importações PDF ainda com arquivo cujas conferências têm algum destes lotes. */
    List<UUID> pdfImportsOfBatches(Collection<UUID> batchIds);

    /** Remoção pendente (lote ainda aberto): exatamente um de {@code fileKey} ou {@code pdfImportId}. */
    record PendingErasure(UUID id, String fileKey, UUID pdfImportId, UUID sourceEventId) {
    }

    /** Registra a remoção pendente (idempotente pela chave ou pelo PDF). @return falso se já estava pendente */
    boolean addPendingErasure(String fileKey, UUID pdfImportId, UUID sourceEventId, Instant at);

    /** Só as pendências que já podem ser concluídas (lotes encerrados, sem conferência aberta, PDF fechado). */
    List<PendingErasure> pendingErasures(int limit);

    void removePendingErasure(UUID id);
}
