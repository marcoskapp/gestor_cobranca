package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.MapOrigin;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImport;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfMapRevision;
import br.com.gestorcobranca.ingestion.domain.model.pdf.RecordDecisions;
import br.com.gestorcobranca.ingestion.domain.model.pdf.VisualReading;
import br.com.gestorcobranca.ingestion.domain.repository.PdfMapRevisionRepository;
import br.com.gestorcobranca.ingestion.domain.service.pdf.LayoutFingerprint;
import br.com.gestorcobranca.ingestion.domain.service.pdf.VisualAssembler;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Aplica um mapa novo a uma importação (proposta da IA, mapa salvo, edição do usuário ou leitura visual): analisa,
 * guarda o mapa e o resumo na importação e grava a revisão no histórico. Participa da transação do chamador.
 */
@Component
class PdfMapApplier {

    private final PdfAnalysis analysis;
    private final PdfMapRevisionRepository revisions;
    private final VisualAssembler visualAssembler = new VisualAssembler();

    PdfMapApplier(PdfAnalysis analysis, PdfMapRevisionRepository revisions) {
        this.analysis = analysis;
        this.revisions = revisions;
    }

    PdfAnalysis.Result apply(PdfImport pdf, ExtractionMap map, MapOrigin origin, UUID templateId) {
        PdfAnalysis.Result result = analysis.analyze(pdf, map);
        String mapJson = analysis.toJson(map);
        pdf.applyMap(mapJson, origin, LayoutFingerprint.of(map), templateId,
                analysis.summaryJson(result, RecordDecisions.none()));
        record(pdf, origin, mapJson, result, templateId);
        return result;
    }

    PdfAnalysis.Result applyVisual(PdfImport pdf, VisualReading reading) {
        pdf.recordVisualResult(analysis.toJson(reading));
        ExtractionMap described = visualAssembler.describedMap(reading);
        PdfAnalysis.Result result = analysis.analyzeVisual(reading, described);
        String mapJson = analysis.toJson(described);
        pdf.applyMap(mapJson, MapOrigin.VISUAL, null, null, analysis.summaryJson(result, RecordDecisions.none()));
        record(pdf, MapOrigin.VISUAL, mapJson, result, null);
        return result;
    }

    private void record(PdfImport pdf, MapOrigin origin, String mapJson, PdfAnalysis.Result result, UUID templateId) {
        revisions.save(new PdfMapRevision(pdf.getId(), pdf.getMapRevision(), origin, mapJson, result.records().size(),
                result.totalsMatch(), !result.hardBlocking().isEmpty(), templateId));
    }
}
