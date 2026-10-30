package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.PdfImportProperties;
import br.com.gestorcobranca.ingestion.application.port.out.PdfDocumentReader.RenderedPage;
import br.com.gestorcobranca.ingestion.application.port.out.PdfModelPort;
import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.MapProblem;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfText;
import br.com.gestorcobranca.ingestion.domain.model.pdf.VisualReading;
import br.com.gestorcobranca.ingestion.domain.service.pdf.ExtractionMapValidator;
import br.com.gestorcobranca.ingestion.domain.service.pdf.MapExplainer;
import br.com.gestorcobranca.ingestion.domain.service.pdf.PromptText;
import br.com.gestorcobranca.shared.ai.LanguageModel.Attachment;
import br.com.gestorcobranca.shared.ai.LanguageModel.ModelMessage;
import br.com.gestorcobranca.shared.ai.LanguageModel.ModelRequest;
import br.com.gestorcobranca.shared.ai.LanguageModel.ModelResponse;
import br.com.gestorcobranca.shared.ai.LanguageModel.ToolCall;
import br.com.gestorcobranca.shared.ai.LanguageModel.ToolDefinition;
import br.com.gestorcobranca.shared.ai.ModelTier;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Chamadas ao modelo da importação PDF ({@link PdfModelPort}), com saída estruturada por ferramenta (JSON Schema
 * versionado em {@code resources/ingestion/pdf}) e validação no servidor:
 * <ul>
 *   <li><strong>Proposta do mapa</strong>: o texto posicionado vai minimizado e mascarado ({@link PromptText}: dígitos
 *       → 9, e-mails mascarados); a proposta é desserializada e validada ({@link ExtractionMapValidator}); se falhar,
 *       o modelo recebe os problemas e corrige (até {@code repairAttempts} vezes). Nenhum valor do devedor é pedido;</li>
 *   <li><strong>Leitura visual</strong> (PDF sem camada de texto): as páginas vão como imagem em lotes de
 *       {@code visualPagesPerCall}; o modelo transcreve os títulos com a confiança de cada um.</li>
 * </ul>
 * Cada chamada devolve o uso (tokens, custo, modelo, latência) para as métricas e o custo da importação. Os metadados
 * da requisição levam só ids opacos.
 */
@Component
class PdfMapProposer {

    static final String MAP_TOOL = "registrar_mapa";
    static final String VISUAL_TOOL = "registrar_leitura";
    private static final Logger LOG = LoggerFactory.getLogger(PdfMapProposer.class);

    private final ObjectProvider<PdfModelPort> model;
    private final JsonCodec json;
    private final PdfImportProperties properties;
    private final ExtractionMapValidator validator = new ExtractionMapValidator();
    private final String mapPrompt;
    private final String visualPrompt;
    private final String mapSchema;
    private final String visualSchema;

    PdfMapProposer(ObjectProvider<PdfModelPort> model, JsonCodec json, PdfImportProperties properties) {
        this.model = model;
        this.json = json;
        this.properties = properties;
        this.mapPrompt = resource("map-proposal-prompt.md");
        this.visualPrompt = resource("visual-reading-prompt.md");
        this.mapSchema = resource("extraction-map.schema.json");
        this.visualSchema = resource("visual-reading.schema.json");
    }

    private static String resource(String name) {
        try {
            return new ClassPathResource("ingestion/pdf/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Recurso da importação PDF ausente: " + name, e);
        }
    }

    boolean available() {
        return model.getIfAvailable() != null;
    }

    /** Uso de uma chamada (custo na moeda do contrato com o provedor). */
    record CallUsage(String purpose, long inputTokens, long outputTokens, BigDecimal cost, String currency, String model,
                     long latencyMs) {
    }

    /** @param map mapa válido, ou nulo se o modelo não chegou a um mapa válido ({@code problems}) */
    record Proposal(ExtractionMap map, List<MapProblem> problems, List<CallUsage> usage) {
    }

    record Visual(VisualReading reading, List<CallUsage> usage) {
    }

    // ------------------------------------------------------------------ proposta do mapa

    Proposal propose(UUID importId, UUID tenantId, PdfText text) {
        PdfModelPort port = requireModel();
        PromptText.Rendered rendered = PromptText.render(text, properties.promptMaxLines());
        String context = json.toJson(context(text, rendered));
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(ModelMessage.user("Texto posicionado do PDF:\n" + rendered.text()));
        List<CallUsage> usage = new ArrayList<>();
        List<MapProblem> problems = List.of();
        for (int attempt = 0; attempt <= properties.repairAttempts(); attempt++) {
            ModelResponse response = port.complete(new ModelRequest(mapPrompt, context, messages,
                    List.of(new ToolDefinition(MAP_TOOL, "Registra o mapa de extração do layout do documento.", mapSchema)),
                    properties.mapMaxOutputTokens(), properties.mapTimeout(), metadata(importId, tenantId, "map"),
                    ModelTier.STANDARD));
            usage.add(usage("map", response));
            Optional<ToolCall> call = response.toolCalls().stream().filter(c -> MAP_TOOL.equals(c.name())).findFirst();
            if (call.isEmpty()) {
                problems = List.of(new MapProblem("pdf_map.ai_no_tool_call", "", "O modelo não registrou o mapa"));
                messages.add(ModelMessage.assistant(response.text(), List.of()));
                messages.add(ModelMessage.user("Registre o mapa chamando a ferramenta " + MAP_TOOL + "."));
                continue;
            }
            ExtractionMap map;
            try {
                map = json.fromJson(call.get().argumentsJson(), ExtractionMap.class);
                problems = validator.validate(map);
            } catch (RuntimeException e) {
                map = null;
                problems = List.of(new MapProblem("pdf_map.ai_malformed", "", "Mapa fora do esquema: " + e.getClass().getSimpleName()));
            }
            if (problems.isEmpty()) {
                return new Proposal(map, List.of(), usage);
            }
            LOG.atInfo().addKeyValue("pdfImportId", importId).addKeyValue("attempt", attempt + 1)
                    .addKeyValue("problems", problems.stream().map(MapProblem::code).distinct().toList())
                    .log("Proposta de mapa da IA não passou na validação");
            messages.add(ModelMessage.assistant(response.text(), List.of(call.get())));
            messages.add(ModelMessage.tool(call.get().id(), json.toJson(Map.of("error", problems))));
        }
        return new Proposal(null, problems, usage);
    }

    private Map<String, Object> context(PdfText text, PromptText.Rendered rendered) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (CanonicalField f : CanonicalField.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("campo", f.column());
            m.put("obrigatorio", f.required());
            m.put("descricao", MapExplainer.describe(f.column()));
            fields.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("camposDoLayoutCanonico", fields);
        out.put("destinosEspeciais", List.of(ExtractionMap.INFO_PREFIX + "<nome>", ExtractionMap.PAID_WHEN_FILLED,
                ExtractionMap.COUNT_TARGET + " (só em controlTotals)"));
        out.put("documento", Map.of("paginas", text.pages().size(), "linhasEnviadas", rendered.lines(),
                "linhasNoDocumento", rendered.totalLines()));
        return out;
    }

    // ------------------------------------------------------------------ leitura visual

    Visual readVisually(UUID importId, UUID tenantId, List<RenderedPage> pages) {
        PdfModelPort port = requireModel();
        String context = json.toJson(Map.of("camposDoLayoutCanonico",
                java.util.Arrays.stream(CanonicalField.values()).map(CanonicalField::column).toList()));
        List<CallUsage> usage = new ArrayList<>();
        List<VisualReading.Item> records = new ArrayList<>();
        List<VisualReading.Control> controls = new ArrayList<>();
        List<VisualReading.Column> columns = new ArrayList<>();
        String documentType = null;
        ExtractionMap.NumberStyle style = null;
        int chunk = Math.max(1, Math.min(20, properties.visualPagesPerCall()));
        for (int from = 0; from < pages.size(); from += chunk) {
            List<RenderedPage> part = pages.subList(from, Math.min(pages.size(), from + chunk));
            List<Attachment> images = part.stream().map(p -> new Attachment("png", p.png())).toList();
            String note = "Páginas " + part.getFirst().page() + " a " + part.getLast().page() + " de " + pages.size()
                    + ", na ordem das imagens.";
            ModelResponse response = port.complete(new ModelRequest(visualPrompt, context,
                    List.of(ModelMessage.user(note, images)),
                    List.of(new ToolDefinition(VISUAL_TOOL, "Registra a transcrição dos títulos das páginas.", visualSchema)),
                    properties.visualMaxOutputTokens(), properties.mapTimeout(), metadata(importId, tenantId, "visual"),
                    ModelTier.STANDARD));
            usage.add(usage("visual", response));
            ToolCall call = response.toolCalls().stream().filter(c -> VISUAL_TOOL.equals(c.name())).findFirst()
                    .orElseThrow(() -> new VisualReadingFailed("O modelo não registrou a leitura das páginas"));
            VisualReading part0;
            try {
                part0 = json.fromJson(call.argumentsJson(), VisualReading.class);
            } catch (RuntimeException e) {
                throw new VisualReadingFailed("Leitura visual fora do esquema");
            }
            int first = part.getFirst().page();
            int last = part.getLast().page();
            for (VisualReading.Item item : part0.records()) {
                // a página precisa ser uma das enviadas nesta chamada
                int page = item.page() >= first && item.page() <= last ? item.page() : first;
                records.add(new VisualReading.Item(page, item.values(), item.confidence()));
            }
            controls.addAll(part0.controls());
            if (columns.isEmpty()) {
                columns.addAll(part0.columns());
            }
            documentType = documentType == null ? part0.documentType() : documentType;
            style = style == null ? part0.numberFormat() : style;
        }
        return new Visual(new VisualReading(documentType, style, columns, records, controls), usage);
    }

    /** A leitura visual não produziu resultado utilizável. */
    static final class VisualReadingFailed extends RuntimeException {
        VisualReadingFailed(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ apoio

    private PdfModelPort requireModel() {
        PdfModelPort port = model.getIfAvailable();
        if (port == null) {
            throw new IllegalStateException("Modelo da importação PDF não configurado");
        }
        return port;
    }

    private static Map<String, String> metadata(UUID importId, UUID tenantId, String purpose) {
        return Map.of("product", "ingestion-pdf", "purpose", purpose, "pdfImportId", importId.toString(),
                "tenantId", tenantId.toString());
    }

    private static CallUsage usage(String purpose, ModelResponse response) {
        long input = response.usage() == null ? 0 : response.usage().promptTokens();
        long output = response.usage() == null ? 0 : response.usage().outputTokens();
        return new CallUsage(purpose, input, output, response.cost() == null ? BigDecimal.ZERO : response.cost().amount(),
                response.cost() == null ? null : response.cost().currency(), response.model(),
                response.latency() == null ? 0 : response.latency().toMillis());
    }
}
