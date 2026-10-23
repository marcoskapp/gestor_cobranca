package br.com.gestorcobranca.ingestion.application.port.out;

import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;

import java.io.InputStream;
import java.util.function.Consumer;

/**
 * Leitura em streaming do conteúdo de um lote (CSV, XLSX ou JSON da API) segundo o layout:
 * cada linha não vazia é entregue ao consumidor, em ordem, sem carregar o conteúdo inteiro em
 * memória. Exceções lançadas pelo consumidor interrompem a leitura e são propagadas como estão.
 *
 * @throws ImportFileException problema do arquivo (cabeçalho, estrutura, codificação) — permanente
 * @throws java.io.UncheckedIOException falha de leitura do armazenamento — transitória
 */
public interface ImportRecordReader {

    void read(InputStream content, FileLayout layout, Consumer<SourceRow> consumer);
}
