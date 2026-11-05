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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

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
    private final TransactionTemplate tx;
    private final Clock clock;

    PdfMapping(PdfImportRepository imports, PdfLayoutTemplateRepository templates, PdfAnalysis analysis,
               PdfMapApplier applier, PdfMapProposer proposer, PdfDocumentReader reader, AuditTrail auditTrail,
               BusinessEventLogger businessEvents, PdfImportMetrics metrics, PdfImportProperties properties,
               PlatformTransactionManager transactionManager, Clock clock) {
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
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * Sem transação própria (o job roda com {@code transactional() = false}): a importação só fica bloqueada nas
     * transações curtas de reivindicar e de gravar o resultado; a leitura do arquivo e as chamadas ao modelo (até
     * minutos) acontecem entre elas, sem conexão nem lock presos (decisão 869). Se a importação saiu de
     * {@code MAPPING} ou teve a revisão alterada no meio (ex.: descartada), o resultado da IA é ignorado.
     *
     * @param useTemplates procura mapa salvo (falso quando o usuário pediu nova proposta à IA)
     * @param lastAttempt  última tentativa do job: falha transitória da IA vira {@code AWAITING_MAP}
     */
    void map(UUID importId, boolean useTemplates, boolean lastAttempt) {
        PdfImport claimed = tx.execute(s -> claim(importId));
        if (claimed == null) {
            return;
        }
        Claim claim = new Claim(importId, claimed.getMapRevision(), clock.instant());
        if (!claimed.hasTextLayer()) {
            readVisually(claim, claimed, lastAttempt);
            return;
        }
        PdfText text = analysis.text(claimed);
        if (useTemplates) {
            Boolean reused = inMapping(claim, pdf -> reuseTemplate(pdf, text, claim.started()));
            if (reused == null || reused) {
                return;
            }
        }
        if (!proposer.available()) {
            locked(claim, pdf -> {
                awaitMap(pdf, "pdf_map.ai_disabled", "Nenhum mapa salvo do credor casou com este layout e a IA está "
                        + "desligada neste ambiente. Descreva o mapa na tela.", claim.started());
                return true;
            });
            return;
        }
        PdfMapProposer.Proposal proposal;
        try {
            proposal = proposer.propose(importId, claimed.getTenantId(), text);
        } catch (ModelException e) {
            locked(claim, pdf -> aiFailure(pdf, e, claim.started(), lastAttempt));
            return;
        }
        if (!locked(claim, pdf -> {
            proposal.usage().forEach(u -> usage(pdf, u));
            if (proposal.map() == null) {
                awaitMap(pdf, "pdf_map.ai_invalid", "A IA não chegou a um mapa válido ("
                        + String.join(", ", proposal.problems().stream().map(MapProblem::code).distinct().toList())
                        + "): ajuste o mapa na tela", claim.started());
            } else {
                finished(pdf, applier.apply(pdf, proposal.map(), MapOrigin.AI, null), MapOrigin.AI, claim.started(),
                        BusinessEventTypes.PDF_IMPORT_MAP_PROPOSED);
            }
            return true;
        })) {
            discardedResult(claim, MapOrigin.AI, proposal.usage());
        }
    }

    /** Reivindicação do mapeamento (importação recebida ou em mapeamento) e revisão do mapa no início. */
    private record Claim(UUID importId, int revision, Instant started) {
    }

    private PdfImport claim(UUID importId) {
        PdfImport pdf = imports.findByIdForUpdate(importId).orElse(null);
        if (pdf == null || !(pdf.getStatus() == PdfImportStatus.RECEIVED || pdf.getStatus() == PdfImportStatus.MAPPING)) {
            return null;
        }
        if (pdf.getStatus() == PdfImportStatus.RECEIVED) {
            pdf.startMapping();
            return imports.save(pdf);
        }
        return pdf;
    }

    /**
     * Reabre a importação bloqueada numa transação curta e aplica a ação; nada faz se ela saiu de {@code MAPPING} ou
     * mudou de revisão desde a reivindicação.
     *
     * @return o retorno da ação, ou {@code false} se a importação não está mais neste mapeamento
     */
    private boolean locked(Claim claim, Predicate<PdfImport> action) {
        return Boolean.TRUE.equals(inMapping(claim, action));
    }

    /** Como {@link #locked}, distinguindo: {@code null} se a importação não está mais neste mapeamento. */
    private Boolean inMapping(Claim claim, Predicate<PdfImport> action) {
        return tx.execute(s -> imports.findByIdForUpdate(claim.importId())
                .filter(pdf -> pdf.getStatus() == PdfImportStatus.MAPPING && pdf.getMapRevision() == claim.revision())
                .map(action::test)
                .orElse(null));
    }

    /** A IA respondeu, mas a importação saiu do mapeamento no meio: o consumo fica só na métrica e no log técnico. */
    private void discardedResult(Claim claim, MapOrigin origin, List<PdfMapProposer.CallUsage> calls) {
        calls.forEach(metrics::usage);
        metrics.mapped(origin.name(), "discarded", Duration.between(claim.started(), clock.instant()));
        LOG.atInfo().addKeyValue("pdfImportId", claim.importId()).addKeyValue("aiCalls", calls.size())
                .log("Resultado da IA ignorado: a importação saiu do mapeamento durante a chamada");
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

    private void readVisually(Claim claim, PdfImport claimed, boolean lastAttempt) {
        if (!proposer.available()) {
            locked(claim, pdf -> {
                pdf.fail("pdf_import.no_text_layer", "O PDF não tem camada de texto (digitalizado) e a leitura visual "
                        + "pela IA está desligada neste ambiente. Peça ao credor o PDF gerado pelo sistema (com texto)");
                imports.save(pdf);
                failedEvent(pdf, "pdf_import.no_text_layer", claim.started());
                return true;
            });
            return;
        }
        PdfMapProposer.Visual visual;
        try {
            byte[] content = analysis.content(claimed);
            List<RenderedPage> pages = new ArrayList<>();
            for (int p = 1; p <= claimed.getPageCount(); p++) {
                pages.add(reader.render(content, p, properties.visualDpi()));
            }
            visual = proposer.readVisually(claim.importId(), claimed.getTenantId(), pages);
        } catch (ModelException e) {
            locked(claim, pdf -> aiFailure(pdf, e, claim.started(), lastAttempt));
            return;
        } catch (PdfMapProposer.VisualReadingFailed e) {
            locked(claim, pdf -> {
                pdf.fail("pdf_import.visual_reading_failed", e.getMessage());
                imports.save(pdf);
                failedEvent(pdf, "pdf_import.visual_reading_failed", claim.started());
                return true;
            });
            return;
        }
        if (!locked(claim, pdf -> {
            visual.usage().forEach(u -> usage(pdf, u));
            finished(pdf, applier.applyVisual(pdf, visual.reading()), MapOrigin.VISUAL, claim.started(),
                    BusinessEventTypes.PDF_IMPORT_MAP_PROPOSED);
            return true;
        })) {
            discardedResult(claim, MapOrigin.VISUAL, visual.usage());
        }
    }

    /**
     * @return {@code true} depois de registrar o desfecho; falha transitória fora da última tentativa é relançada
     *     (desfaz só a transação curta, a importação segue em mapeamento e o job tenta de novo)
     */
    private boolean aiFailure(PdfImport pdf, ModelException e, Instant started, boolean lastAttempt) {
        boolean permanent = e instanceof ModelRejectedException;
        if (!permanent && !lastAttempt) {
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
        return true;
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
