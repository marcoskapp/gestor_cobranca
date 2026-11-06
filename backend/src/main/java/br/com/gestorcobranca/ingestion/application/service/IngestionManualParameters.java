package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.PdfImportProperties;
import br.com.gestorcobranca.shared.manual.ManualParameterSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/** Tamanho máximo do PDF da importação inteligente (em MB inteiros, garantidos por {@link PdfImportProperties}). */
@Component
class IngestionManualParameters implements ManualParameterSource {

    private final PdfImportProperties properties;

    IngestionManualParameters(PdfImportProperties properties) {
        this.properties = properties;
    }

    @Override
    public String module() {
        return "ingestion";
    }

    @Override
    public Map<String, BigDecimal> parameters() {
        return Map.of("pdfMaxFileSizeMb", BigDecimal.valueOf(properties.maxFileSize().toMegabytes()));
    }
}
