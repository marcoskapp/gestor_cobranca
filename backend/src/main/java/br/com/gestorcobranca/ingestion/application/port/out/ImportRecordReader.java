package br.com.gestorcobranca.ingestion.application.port.out;

import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;

import java.io.InputStream;
import java.util.List;

/**
 * Leitura em streaming do conteúdo de um lote (CSV, XLSX ou JSON da API) segundo o layout:
 * cada linha não vazia é entregue ao destino, em ordem, sem carregar o conteúdo inteiro em
 * memória. Exceções lançadas pelo destino interrompem a leitura e são propagadas como estão.
 * <p>
 * Proteções do adaptador: codificação estrita (sem substituição silenciosa), XLSX aberto com
 * limites de descompactação (zip bomb) e sem entidades externas (XXE), fórmulas nunca avaliadas
 * (vale o último valor calculado gravado na planilha).
 *
 * @throws ImportFileException problema do arquivo (cabeçalho, estrutura, codificação) — permanente
 * @throws java.io.UncheckedIOException falha de leitura do armazenamento — transitória
 */
public interface ImportRecordReader {

    void read(InputStream content, FileLayout layout, RecordSink sink);

    /** Destino das linhas lidas. */
    interface RecordSink {

        void row(SourceRow row);

        /**
         * Colunas do arquivo (ou chaves da API) não mapeadas pelo layout. Chamado uma vez: após o
         * cabeçalho (CSV/XLSX) ou ao fim da leitura (API).
         */
        default void ignoredColumns(List<String> columns) {
        }
    }
}
