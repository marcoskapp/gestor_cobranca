package br.com.gestorcobranca.ingestion.infrastructure.parsing;

import br.com.gestorcobranca.ingestion.application.port.out.ImportRecordReader;
import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.function.Consumer;

/** Seleciona o leitor pelo formato do layout. */
@Component
class FormatImportRecordReader implements ImportRecordReader {

    private final CsvRecordReader csv = new CsvRecordReader();
    private final XlsxRecordReader xlsx = new XlsxRecordReader();
    private final JsonRecordReader json;

    FormatImportRecordReader(JsonCodec jsonCodec) {
        this.json = new JsonRecordReader(jsonCodec.mapper());
    }

    @Override
    public void read(InputStream content, FileLayout layout, Consumer<SourceRow> consumer) {
        switch (layout.format()) {
            case CSV -> csv.read(content, layout, consumer);
            case XLSX -> xlsx.read(content, layout, consumer);
            case JSON -> json.read(content, layout, consumer);
        }
    }
}
