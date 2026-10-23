package br.com.gestorcobranca.ingestion.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Parâmetros da ingestão ({@code app.ingestion.*}). Os limites protegem a aplicação de arquivos
 * maliciosos ou acidentalmente enormes; os padrões atendem cargas de até um milhão de títulos.
 *
 * @param maxFileSize                tamanho máximo do arquivo enviado (padrão 50 MB)
 * @param maxRows                    linhas de dados por lote (padrão 1.000.000); verificado na leitura prévia
 * @param maxApiRecords              registros por requisição da API de importação (padrão 1.000)
 * @param blockSize                  linhas por bloco: cada bloco confirma contadores, ocorrências e checkpoint (padrão 500)
 * @param sliceDuration              tempo máximo de uma execução do job; ao atingi-lo, o lote continua em
 *                                   nova execução a partir do checkpoint (padrão 2 min, bem abaixo do lease)
 * @param maxIssuesPerBatch          ocorrências gravadas por lote; além disso são apenas contadas (padrão 50.000)
 * @param xlsxMaxUncompressedSize    soma dos tamanhos descompactados das partes do XLSX (padrão 1 GB)
 * @param xlsxMaxSharedStringsSize   tamanho descompactado da tabela de textos, que fica em memória (padrão 100 MB)
 * @param xlsxMaxEntries             partes no pacote XLSX (padrão 1.000)
 * @param xlsxMaxCompressionRatio    razão máxima de descompactação por parte (padrão 100:1)
 */
@ConfigurationProperties(prefix = "app.ingestion")
public record IngestionProperties(DataSize maxFileSize, int maxRows, int maxApiRecords, int blockSize,
                                  Duration sliceDuration, int maxIssuesPerBatch, DataSize xlsxMaxUncompressedSize,
                                  DataSize xlsxMaxSharedStringsSize, int xlsxMaxEntries, int xlsxMaxCompressionRatio) {

    public IngestionProperties {
        maxFileSize = maxFileSize == null ? DataSize.ofMegabytes(50) : maxFileSize;
        maxRows = maxRows <= 0 ? 1_000_000 : maxRows;
        maxApiRecords = maxApiRecords <= 0 ? 1000 : maxApiRecords;
        blockSize = blockSize <= 0 ? 500 : blockSize;
        sliceDuration = sliceDuration == null || sliceDuration.isNegative() || sliceDuration.isZero()
                ? Duration.ofMinutes(2) : sliceDuration;
        maxIssuesPerBatch = maxIssuesPerBatch <= 0 ? 50_000 : maxIssuesPerBatch;
        xlsxMaxUncompressedSize = xlsxMaxUncompressedSize == null ? DataSize.ofGigabytes(1) : xlsxMaxUncompressedSize;
        xlsxMaxSharedStringsSize = xlsxMaxSharedStringsSize == null ? DataSize.ofMegabytes(100) : xlsxMaxSharedStringsSize;
        xlsxMaxEntries = xlsxMaxEntries <= 0 ? 1000 : xlsxMaxEntries;
        xlsxMaxCompressionRatio = xlsxMaxCompressionRatio <= 0 ? 100 : xlsxMaxCompressionRatio;
    }

    /** Valores padrão (testes e ferramentas). */
    public static IngestionProperties defaults() {
        return new IngestionProperties(null, 0, 0, 0, null, 0, null, null, 0, 0);
    }
}
