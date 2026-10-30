package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.PdfImportProperties;
import br.com.gestorcobranca.ingestion.application.port.out.PdfDocumentReader;
import br.com.gestorcobranca.ingestion.application.port.out.PdfDocumentReader.RenderedPage;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.MapOrigin;
import br.com.gestorcobranca.ingestion.domain.model.pdf.MapProblem;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImport;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImportStatus;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfLayoutTemplate;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfText;
import br.com.gestorcobranca.ingestion.domain.repository.PdfImportRepository;
import br.com.gestorcobranca.ingestion.domain.repository.PdfLayoutTemplateRepository;
import br.com.gestorcobranca.ingestion.domain.service.pdf.LayoutFingerprint;
import br.com.gestorcobranca.shared.ai.LanguageModel.ModelException;
import br.com.gestorcobranca.shared.ai.LanguageModel.ModelRejectedException;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mapeamento de uma importação PDF (job {@link PdfMappingJobHandler}), na ordem:
 * <ol>
 *   <li><strong>Mapa salvo</strong>: com camada de texto, o primeiro mapa ativo do credor cujo layout casa com o
 *       documento ({@link LayoutFingerprint#matches}) é aplicado direto, sem IA ({@code pdf_import.map_reused});</li>
 *   <li><strong>Proposta da IA</strong> a partir do texto posicionado e mascarado ({@code pdf_import.map_proposed});</li>
 *   <li><strong>Leitura visual</strong> pela IA quando não há camada de texto;</li>
 *   <li>sem IA (desligada, indisponível ou proposta inválida): {@code AWAITING_MAP} — o usuário escreve o mapa; PDF
 *       sem texto e sem IA: {@code FAILED} (leitura por OCR externo depende de aprovação, ver pendências).</li>
 * </ol>
 * Idempotente: só age em importação {@code RECEIVED}/{@code MAPPING}.
 */
@Component
class PdfMapping {

    static final String RESOURCE = PdfImportService.RESOURCE;
    private static final Logger LOG = LoggerFactory.getLogger(PdfMapping.class);

    private final PdfImportRepository imports;
    private final PdfLayoutTemplateRepository templates;
    private final PdfAnalysis analysis;
    private final PdfMapApplier applier;
    private final PdfMapProposer proposer;
    private final PdfDocumentReader reader;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final PdfImportMetrics metrics;
    private final PdfImportProperties properties;
    private final Clock clock;

    PdfMapping(PdfImportRepository imports, PdfLayoutTemplateRepository templates, PdfAnalysis analysis,
               PdfMapApplier applier, PdfMapProposer proposer, PdfDocumentReader reader, AuditTrail auditTrail,
               BusinessEventLogger businessEvents, PdfImportMetrics metrics, PdfImportProperties properties, Clock clock) {
        this.imports = imports;
        this.templates = templates;
        this.analysis = analysis;
        this.applier = applier;
        this.proposer = proposer;
        this.reader = reader;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param useTemplates procura mapa salvo (falso quando o usuário pediu nova proposta à IA)
     * @param lastAttempt  última tentativa do job: falha transitória da IA vira {@code AWAITING_MAP}
     */
    @Transactional
    void map(UUID importId, boolean useTemplates, boolean lastAttempt) {
        PdfImport pdf = imports.findByIdForUpdate(importId).orElse(null);
        if (pdf == null || !(pdf.getStatus() == PdfImportStatus.RECEIVED || pdf.getStatus() == PdfImportStatus.MAPPING)) {
            return;
        }
        if (pdf.getStatus() == PdfImportStatus.RECEIVED) {
            pdf.startMapping();
        }
        Instant started = clock.instant();
        if (pdf.hasTextLayer()) {
            PdfText text = analysis.text(pdf);
            if (useTemplates && reuseTemplate(pdf, text, started)) {
                return;
            }
            propose(pdf, text, started, lastAttempt);
        } else {
            readVisually(pdf, started, lastAttempt);
        }
    }

    private boolean reuseTemplate(PdfImport pdf, PdfText text, Instant started) {
        for (PdfLayoutTemplate template : templates.findActiveByCreditor(pdf.getCreditorId())) {
            ExtractionMap map = analysis.parseMap(template.getMap());
            if (!LayoutFingerprint.matches(text, map)) {
                continue;
            }
            PdfAnalysis.Result result = applier.apply(pdf, map, MapOrigin.TEMPLATE, template.getId());
            template.used(clock.instant());
            templates.save(template);
            finished(pdf, result, MapOrigin.TEMPLATE, started, BusinessEventTypes.PDF_IMPORT_MAP_REUSED);
            return true;
        }
        return false;
    }

    private void propose(PdfImport pdf, PdfText text, Instant started, boolean lastAttempt) {
        if (!proposer.available()) {
            awaitMap(pdf, "pdf_map.ai_disabled", "Nenhum mapa salvo do credor casou com este layout e a IA está "
                    + "desligada neste ambiente. Descreva o mapa na tela.", started);
            return;
        }
        PdfMapProposer.Proposal proposal;
        try {
            proposal = proposer.propose(pdf.getId(), pdf.getTenantId(), text);
        } catch (ModelException e) {
            aiFailure(pdf, e, started, lastAttempt);
            return;
        }
        proposal.usage().forEach(u -> usage(pdf, u));
        if (proposal.map() == null) {
            awaitMap(pdf, "pdf_map.ai_invalid", "A IA não chegou a um mapa válido ("
                    + String.join(", ", proposal.problems().stream().map(MapProblem::code).distinct().toList())
                    + "): ajuste o mapa na tela", started);
            return;
        }
        PdfAnalysis.Result result = applier.apply(pdf, proposal.map(), MapOrigin.AI, null);
        finished(pdf, result, MapOrigin.AI, started, BusinessEventTypes.PDF_IMPORT_MAP_PROPOSED);
    }

    private void readVisually(PdfImport pdf, Instant started, boolean lastAttempt) {
        if (!proposer.available()) {
            pdf.fail("pdf_import.no_text_layer", "O PDF não tem camada de texto (digitalizado) e a leitura visual "
                    + "pela IA está desligada neste ambiente. Peça ao credor o PDF gerado pelo sistema (com texto)");
            imports.save(pdf);
            failedEvent(pdf, "pdf_import.no_text_layer", started);
            return;
        }
        PdfMapProposer.Visual visual;
        try {
            byte[] content = analysis.content(pdf);
            List<RenderedPage> pages = new ArrayList<>();
            for (int p = 1; p <= pdf.getPageCount(); p++) {
                pages.add(reader.render(content, p, properties.visualDpi()));
            }
            visual = proposer.readVisually(pdf.getId(), pdf.getTenantId(), pages);
        } catch (ModelException e) {
            aiFailure(pdf, e, started, lastAttempt);
            return;
        } catch (PdfMapProposer.VisualReadingFailed e) {
            pdf.fail("pdf_import.visual_reading_failed", e.getMessage());
            imports.save(pdf);
            failedEvent(pdf, "pdf_import.visual_reading_failed", started);
            return;
        }
        visual.usage().forEach(u -> usage(pdf, u));
        PdfAnalysis.Result result = applier.applyVisual(pdf, visual.reading());
        finished(pdf, result, MapOrigin.VISUAL, started, BusinessEventTypes.PDF_IMPORT_MAP_PROPOSED);
    }

    private void aiFailure(PdfImport pdf, ModelException e, Instant started, boolean lastAttempt) {
        boolean permanent = e instanceof ModelRejectedException;
        if (!permanent && !lastAttempt) {
            // falha transitória: o job tenta de novo (a transação desfeita mantém a importação em mapeamento)
            throw e;
        }
        LOG.atWarn().addKeyValue("pdfImportId", pdf.getId()).addKeyValue("outcome", e.code())
                .log("IA indisponível para a importação PDF; segue sem proposta");
        if (pdf.hasTextLayer()) {
            awaitMap(pdf, "pdf_map.ai_unavailable", "A IA não respondeu (" + e.code() + "). Descreva o mapa na tela "
                    + "ou peça nova proposta mais tarde.", started);
        } else {
            pdf.fail("pdf_import.ai_unavailable", "A leitura visual não pôde ser feita (" + e.code() + "). Envie o PDF de novo mais tarde");
            imports.save(pdf);
            failedEvent(pdf, "pdf_import.ai_unavailable", started);
        }
    }

    private void awaitMap(PdfImport pdf, String code, String message, Instant started) {
        pdf.awaitMap(code, message);
        imports.save(pdf);
        metrics.mapped("NONE", code, Duration.between(started, clock.instant()));
        auditTrail.record(AuditEntry.of("pdf_import.awaiting_map", RESOURCE, pdf.getId()).creditor(pdf.getCreditorId())
                .meta("code", code));
        failedEvent(pdf, code, started);
    }

    private void failedEvent(PdfImport pdf, String code, Instant started) {
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PDF_IMPORT_MAPPING_FAILED)
                .creditor(pdf.getCreditorId()).portfolio(pdf.getPortfolioId()).reference(RESOURCE, pdf.getId())
                .durationMs(Duration.between(started, clock.instant()).toMillis())
                .attr("code", code).attr("pages", pdf.getPageCount()).attr("textLayer", pdf.hasTextLayer()));
    }

    private void usage(PdfImport pdf, PdfMapProposer.CallUsage u) {
        pdf.addUsage(u.inputTokens(), u.outputTokens(), u.cost(), u.currency(), u.model(), u.latencyMs());
        metrics.usage(u);
    }

    private void finished(PdfImport pdf, PdfAnalysis.Result result, MapOrigin origin, Instant started, String eventType) {
        imports.save(pdf);
        Duration duration = Duration.between(started, clock.instant());
        metrics.mapped(origin.name(), result.totalsMatch() ? "totals_match" : "totals_mismatch", duration);
        auditTrail.record(AuditEntry.of("pdf_import.map_applied", RESOURCE, pdf.getId()).creditor(pdf.getCreditorId())
                .created("origin", origin.name()).created("revision", pdf.getMapRevision())
                .meta("templateId", pdf.getTemplateId()).meta("fingerprint", pdf.getFingerprint())
                .meta("records", result.records().size()).meta("totalsMatch", result.totalsMatch()));
        businessEvents.log(BusinessEvent.of(eventType)
                .creditor(pdf.getCreditorId()).portfolio(pdf.getPortfolioId()).reference(RESOURCE, pdf.getId())
                .amount(principal(result)).durationMs(duration.toMillis())
                .attr("origin", origin.name()).attr("revision", pdf.getMapRevision()).attr("pages", pdf.getPageCount())
                .attr("textLayer", pdf.hasTextLayer()).attr("records", result.records().size())
                .attr("debtors", result.records().stream().map(r -> r.debtorIndex()).distinct().count())
                .attr("totalsMatch", result.totalsMatch()).attr("blocking", !result.hardBlocking().isEmpty())
                .attr("templateId", pdf.getTemplateId()).attr("aiCalls", pdf.getAiCalls())
                .attr("inputTokens", pdf.getAiInputTokens()).attr("outputTokens", pdf.getAiOutputTokens())
                .attr("aiCost", pdf.getAiCost()).attr("aiCurrency", pdf.getAiCurrency()).attr("model", pdf.getAiModel()));
        LOG.atInfo().addKeyValue("pdfImportId", pdf.getId()).addKeyValue("origin", origin)
                .addKeyValue("records", result.records().size()).addKeyValue("pages", pdf.getPageCount())
                .addKeyValue("durationMs", duration.toMillis()).log("Mapa aplicado à importação PDF");
    }

    private static BigDecimal principal(PdfAnalysis.Result result) {
        BigDecimal total = BigDecimal.ZERO;
        for (var r : result.records()) {
            String v = r.values().get(br.com.gestorcobranca.ingestion.domain.model.CanonicalField.PRINCIPAL_AMOUNT);
            if (v != null && r.typed().contains(br.com.gestorcobranca.ingestion.domain.model.CanonicalField.PRINCIPAL_AMOUNT)) {
                total = total.add(new BigDecimal(v));
            }
        }
        return total;
    }
}
