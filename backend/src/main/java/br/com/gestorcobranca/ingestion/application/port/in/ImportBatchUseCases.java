package br.com.gestorcobranca.ingestion.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lotes de importação de carteira (M02/F04). O recebimento grava o conteúdo original no
 * armazenamento e enfileira o processamento assíncrono; o resultado é acompanhado pelo status
 * e pelos contadores do lote e pelas ocorrências por linha.
 * <p>
 * Reenvio do mesmo conteúdo (SHA-256) já importado com sucesso na carteira é recusado
 * ({@code import.duplicate_file}) a menos que o usuário confirme o reprocessamento com
 * {@code force} e justificativa (auditado). Todas as operações respeitam o escopo por credor.
 * <p>
 * Reimportação idempotente: cada linha é casada pelo identificador externo do título (e o
 * devedor pelo documento); reprocessar um arquivo — inteiro ou após uma queda — não duplica
 * devedores, contatos, títulos, lançamentos ou saldos.
 */
public interface ImportBatchUseCases {

    /** Envio de arquivo CSV ou XLSX. */
    ImportBatchView uploadFile(UploadFileCommand command);

    /**
     * Registros recebidos pela API. A chave de idempotência é obrigatória: repetir a chamada com
     * a mesma chave e o mesmo conteúdo devolve o mesmo lote.
     */
    ImportBatchView importRecords(ImportRecordsCommand command, String idempotencyKey);

    /** Novo lote com o arquivo de um lote encerrado (ex.: importar o que foi pré-visualizado). */
    ImportBatchView reprocess(UUID batchId, ReprocessCommand command);

    /**
     * Cancela o lote: ainda na fila, de imediato; em processamento, ao fim do bloco corrente (o que
     * já foi gravado permanece e pode ser completado por nova carga do mesmo arquivo).
     */
    ImportBatchView cancel(UUID batchId, String reason);

    ImportBatchView get(UUID batchId);

    PageResponse<ImportBatchView> search(BatchSearch filter, PageQuery page);

    /** @param severity ERROR ou WARNING (opcional) */
    PageResponse<ImportIssueView> issues(UUID batchId, String severity, PageQuery page);

    /**
     * Relatório de ocorrências em CSV, escrito em streaming. Registra {@code DATA_EXPORTED} no
     * log de acesso. Valida o acesso antes de escrever qualquer byte.
     */
    void exportIssues(UUID batchId, OutputStream output);

    /**
     * Arquivo original do lote (contém dados pessoais em claro): exige {@code DEBTOR_SENSITIVE_VIEW}
     * e registra {@code FILE_DOWNLOADED}. O chamador fecha o stream.
     */
    DownloadableFile originalFile(UUID batchId);

    /** @param size tamanho em bytes */
    record DownloadableFile(String fileName, String contentType, long size, InputStream content) {
    }

    /** Conteúdo do arquivo enviado, que pode ser aberto mais de uma vez (hash e gravação). */
    @FunctionalInterface
    interface FileContent {
        InputStream open() throws IOException;
    }

    /**
     * @param layoutId         nulo = layout padrão (formato pela extensão: .csv ou .xlsx)
     * @param mode             IMPORT (padrão) ou VALIDATE_ONLY
     * @param force            confirma reprocessamento de arquivo já importado na carteira
     * @param reason           justificativa obrigatória quando {@code force} é usado sobre arquivo já importado
     * @param decimalSeparator COMMA ou DOT para as colunas monetárias ambíguas ou misturadas (opcional; sem ele, o
     *                         lote com coluna ambígua falha antes de gravar)
     */
    record UploadFileCommand(UUID portfolioId, UUID layoutId, String mode, boolean force, String reason,
                             String fileName, String contentType, long size, FileContent content,
                             String decimalSeparator) {

        public UploadFileCommand(UUID portfolioId, UUID layoutId, String mode, boolean force, String reason,
                                 String fileName, String contentType, long size, FileContent content) {
            this(portfolioId, layoutId, mode, force, reason, fileName, contentType, size, content, null);
        }
    }

    /**
     * @param records       registros com as chaves canônicas (ver documentação do layout padrão); valores string,
     *                      número ou booleano — números não têm ambiguidade de separador, strings passam pela análise
     *                      do formato por coluna
     * @param numberFormat  interpretação das colunas monetárias em texto ({@code valor_principal → COMMA|DOT}), opcional
     * @param controlTotals totais de controle do lote, opcionais: divergência falha o lote antes de gravar
     */
    record ImportRecordsCommand(UUID portfolioId, String mode, boolean force, String reason,
                                List<Map<String, Object>> records, Map<String, String> numberFormat,
                                ControlTotalsCommand controlTotals) {

        public ImportRecordsCommand(UUID portfolioId, String mode, boolean force, String reason,
                                    List<Map<String, Object>> records) {
            this(portfolioId, mode, force, reason, records, null, null);
        }
    }

    /** Totais de controle informados pela integração (ao menos um). */
    record ControlTotalsCommand(Long rows, BigDecimal principalTotal, BigDecimal balanceTotal,
                                BigDecimal contractAmountTotal) {
    }

    /** @param reason obrigatória quando o arquivo já foi importado com sucesso na carteira */
    record ReprocessCommand(String mode, String reason) {
    }

    /** Filtros opcionais. */
    record BatchSearch(UUID creditorId, UUID portfolioId, String status, String source, Instant createdFrom,
                       Instant createdTo) {
    }

    /**
     * @param requestedBy      usuário que enviou o lote
     * @param lastProcessedRow checkpoint do processamento
     * @param issuesTruncated  há mais ocorrências do que as gravadas (limite por lote)
     * @param reviewId         conferência que acompanha a prévia ou que gerou a importação
     * @param numberFormat     interpretação aplicada às colunas monetárias (coluna → COMMA, DOT, PER_VALUE_*)
     */
    record ImportBatchView(UUID id, UUID creditorId, UUID portfolioId, UUID layoutId, String layoutCode,
                           int layoutVersion, String source, String mode, String status, String statusReason,
                           String fileName, long fileSize, String fileSha256, boolean forced, String forceReason,
                           UUID duplicateOfBatchId, UUID reprocessOfBatchId, Counters counters, int issuesRecorded,
                           boolean issuesTruncated, Long totalRows, Integer progressPercent, long lastProcessedRow,
                           Instant cancelRequestedAt, UUID requestedBy, Instant createdAt, Instant startedAt,
                           Instant finishedAt, UUID reviewId, Map<String, String> numberFormat) {
    }

    /**
     * @param errors          ocorrências de erro (uma linha pode ter vários)
     * @param warnings        ocorrências de aviso
     * @param titlesSkipped   linhas válidas não gravadas (título informado como pago/cancelado pelo credor)
     * @param titlesSuspended títulos suspensos pela carga a pedido do credor (suspenso, contestado, pago, cancelado)
     * @param principalTotal  principal das linhas válidas gravadas (ou validadas, na pré-visualização)
     * @param rowsExcluded    linhas excluídas na conferência (não gravadas)
     */
    record Counters(int rowsRead, int rowsValid, int rowsWithError, int errors, int warnings, int debtorsCreated,
                    int debtorsUpdated, int titlesCreated, int titlesUpdated, int titlesUnchanged, int titlesSkipped,
                    int titlesSuspended, BigDecimal principalTotal, int rowsExcluded) {
    }

    /** @param valueExcerpt trecho mascarado do valor recebido */
    record ImportIssueView(UUID id, long rowNumber, String severity, String field, String code, String message,
                           String valueExcerpt) {
    }
}
