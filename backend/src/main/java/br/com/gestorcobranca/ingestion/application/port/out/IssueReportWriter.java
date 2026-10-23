package br.com.gestorcobranca.ingestion.application.port.out;

import java.io.OutputStream;

/** Formata o relatório de ocorrências (CSV) diretamente no stream de saída. */
public interface IssueReportWriter {

    /** Abre o relatório escrevendo o cabeçalho; o chamador fecha o relatório (não o stream). */
    Report open(OutputStream output);

    interface Report extends AutoCloseable {

        void write(IssueLine line);

        @Override
        void close();
    }
}
