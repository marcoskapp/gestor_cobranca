package br.com.gestorcobranca.ingestion.infrastructure.parsing;

import br.com.gestorcobranca.ingestion.application.IngestionProperties;
import br.com.gestorcobranca.ingestion.application.port.out.ImportRecordReader;
import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/** Seleciona o leitor pelo formato do layout. */
@Component
class FormatImportRecordReader implements ImportRecordReader {

    private final CsvRecordReader csv = new CsvRecordReader();
    private final XlsxRecordReader xlsx;
    private final JsonRecordReader json;

    FormatImportRecordReader(JsonCodec jsonCodec, IngestionProperties properties) {
        this.json = new JsonRecordReader(jsonCodec.mapper());
        this.xlsx = new XlsxRecordReader(new XlsxRecordReader.XlsxLimits(properties.xlsxMaxUncompressedSize().toBytes(),
                properties.xlsxMaxSharedStringsSize().toBytes(), properties.xlsxMaxEntries(),
                properties.xlsxMaxCompressionRatio()));
    }

    @Override
    public void read(InputStream content, FileLayout layout, RecordSink sink) {
        switch (layout.format()) {
            case CSV -> csv.read(content, layout, sink);
            case XLSX -> xlsx.read(content, layout, sink);
            case JSON -> json.read(content, layout, sink);
        }
    }
}
