package br.com.gestorcobranca.publicapi.application.port.out;

import java.io.OutputStream;
import java.util.List;

/** Escrita de CSV (separador ";", UTF-8 com BOM) com células neutralizadas contra fórmulas. */
public interface CsvWriter {

    Sheet open(OutputStream output, List<String> header);

    interface Sheet extends AutoCloseable {

        void row(List<?> values);

        @Override
        void close();
    }
}
