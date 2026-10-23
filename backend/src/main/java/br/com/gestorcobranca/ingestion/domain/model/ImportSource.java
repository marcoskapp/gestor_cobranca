package br.com.gestorcobranca.ingestion.domain.model;

/** Origem do lote: arquivo enviado (CSV/XLSX) ou registros recebidos pela API. */
public enum ImportSource {
    CSV(ImportFormat.CSV),
    XLSX(ImportFormat.XLSX),
    API(ImportFormat.JSON);

    private final ImportFormat format;

    ImportSource(ImportFormat format) {
        this.format = format;
    }

    public ImportFormat format() {
        return format;
    }

    public static ImportSource of(ImportFormat format) {
        return switch (format) {
            case CSV -> CSV;
            case XLSX -> XLSX;
            case JSON -> API;
        };
    }
}
