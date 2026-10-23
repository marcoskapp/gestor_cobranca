package br.com.gestorcobranca.ingestion.application.port.out;

import br.com.gestorcobranca.ingestion.domain.model.ImportFormat;

import java.io.OutputStream;
import java.util.List;

/** Gera o modelo vazio de um layout (somente a linha de cabeçalho) em CSV ou XLSX. */
public interface LayoutTemplateWriter {

    /**
     * @param delimiter delimitador do CSV (ignorado em XLSX)
     */
    void write(ImportFormat format, char delimiter, List<String> headers, OutputStream output);
}
