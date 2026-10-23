package br.com.gestorcobranca.ingestion.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Parâmetros da ingestão ({@code app.ingestion.*}).
 *
 * @param maxFileSize        tamanho máximo do arquivo enviado
 * @param maxApiRecords      registros por requisição da API de importação
 * @param blockSize          linhas por bloco (cada bloco confirma o checkpoint)
 * @param maxIssuesPerBatch  ocorrências gravadas por lote; além disso são apenas contadas
 */
@ConfigurationProperties(prefix = "app.ingestion")
public record IngestionProperties(DataSize maxFileSize, int maxApiRecords, int blockSize, int maxIssuesPerBatch) {

    public IngestionProperties {
        maxFileSize = maxFileSize == null ? DataSize.ofMegabytes(50) : maxFileSize;
        maxApiRecords = maxApiRecords <= 0 ? 1000 : maxApiRecords;
        blockSize = blockSize <= 0 ? 500 : blockSize;
        maxIssuesPerBatch = maxIssuesPerBatch <= 0 ? 50_000 : maxIssuesPerBatch;
    }
}
