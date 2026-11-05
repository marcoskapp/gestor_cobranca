package br.com.gestorcobranca.ingestion.application.port.out;

import java.time.Instant;
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
}
