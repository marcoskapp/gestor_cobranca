package br.com.gestorcobranca.ingestion.domain.model.pdf;

import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.NumberStyle;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TotalScope;

import java.util.List;
import java.util.Map;

/**
 * Leitura visual de um PDF sem camada de texto (transcrição do modelo, a conferir pelo usuário). Contém dados pessoais:
 * guardada cifrada na importação.
 */
public record VisualReading(String documentType, NumberStyle numberFormat, List<Column> columns, List<Item> records,
                            List<Control> controls) {

    public VisualReading {
        numberFormat = numberFormat == null ? NumberStyle.COMMA : numberFormat;
        columns = columns == null ? List.of() : List.copyOf(columns);
        records = records == null ? List.of() : List.copyOf(records);
        controls = controls == null ? List.of() : List.copyOf(controls);
    }

    public record Column(String header, String target) {
    }

    /** @param values campo canônico ou {@code info:*} → texto como impresso */
    public record Item(int page, Map<String, String> values, double confidence) {

        public Item {
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }

    public record Control(String label, String target, String value, TotalScope scope, int page) {
    }
}
