package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Job do mapeamento de uma importação PDF ({@link PdfMapping}). Falha transitória da IA é repetida; na última
 * tentativa a importação segue para o mapa manual.
 */
@Component
class PdfMappingJobHandler implements JobHandler<PdfMappingJobHandler.Payload> {

    static final String TYPE = "ingestion.pdf-map";
    private static final int MAX_ATTEMPTS = 3;

    private final PdfMapping mapping;

    PdfMappingJobHandler(PdfMapping mapping) {
        this.mapping = mapping;
    }

    /** @param useTemplates procurar mapa salvo do credor antes da IA */
    record Payload(UUID pdfImportId, boolean useTemplates) {
    }

    static String deduplicationKey(UUID pdfImportId) {
        return "pdf-map:" + pdfImportId;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public int maxAttempts() {
        return MAX_ATTEMPTS;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        mapping.map(payload.pdfImportId(), payload.useTemplates(), context.attempt() >= MAX_ATTEMPTS);
    }
}
