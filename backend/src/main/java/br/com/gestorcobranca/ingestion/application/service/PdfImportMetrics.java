package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImport;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Métricas {@code ingestion.pdf.*} (catálogo em {@code docs/tecnico/logs-e-metricas.md}): recebimento, páginas,
 * mapeamento por origem e duração, tokens e custo por finalidade, aprovação e descarte. As chamadas ao Bedrock têm
 * também as métricas do cliente do kernel ({@code ingestion.pdf.bedrock.*} e {@code integration.calls}). Tags só de
 * baixa cardinalidade.
 */
@Component
class PdfImportMetrics {

    private final MeterRegistry registry;

    PdfImportMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void received(PdfImport pdf) {
        String layer = pdf.hasTextLayer() ? "text" : "image";
        registry.counter("ingestion.pdf.received", "channel", pdf.getChannel().name(), "layer", layer).increment();
        DistributionSummary.builder("ingestion.pdf.pages").tag("layer", layer).publishPercentiles(0.5, 0.95)
                .register(registry).record(pdf.getPageCount());
    }

    void rejected(String reason) {
        registry.counter("ingestion.pdf.rejected", "reason", reason).increment();
    }

    void mapped(String origin, String outcome, Duration duration) {
        registry.counter("ingestion.pdf.mapping", "origin", origin, "outcome", outcome).increment();
        Timer.builder("ingestion.pdf.mapping.duration").tag("origin", origin).tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95).register(registry).record(duration);
    }

    void usage(PdfMapProposer.CallUsage usage) {
        if (usage.inputTokens() > 0) {
            registry.counter("ingestion.pdf.ai.tokens", "purpose", usage.purpose(), "type", "input").increment(usage.inputTokens());
        }
        if (usage.outputTokens() > 0) {
            registry.counter("ingestion.pdf.ai.tokens", "purpose", usage.purpose(), "type", "output").increment(usage.outputTokens());
        }
        if (usage.cost() != null && usage.cost().signum() > 0) {
            registry.counter("ingestion.pdf.ai.cost", "purpose", usage.purpose(), "currency",
                    usage.currency() == null ? "USD" : usage.currency()).increment(usage.cost().doubleValue());
        }
    }

    void mapEdited() {
        registry.counter("ingestion.pdf.map.edits").increment();
    }

    void approved(PdfImport pdf, boolean totalsMatch, int records) {
        registry.counter("ingestion.pdf.approved", "origin", pdf.getMapOrigin().name(), "totals",
                totalsMatch ? "match" : "justified").increment();
        DistributionSummary.builder("ingestion.pdf.records").publishPercentiles(0.5, 0.95).register(registry).record(records);
        if (pdf.getCreatedAt() != null && pdf.getApprovedAt() != null) {
            Timer.builder("ingestion.pdf.time_to_approval").tag("origin", pdf.getMapOrigin().name())
                    .publishPercentiles(0.5, 0.95).register(registry)
                    .record(Duration.between(pdf.getCreatedAt(), pdf.getApprovedAt()));
        }
    }

    void discarded() {
        registry.counter("ingestion.pdf.discarded").increment();
    }

    void templateSaved() {
        registry.counter("ingestion.pdf.templates.saved").increment();
    }

    void filesDeleted(int count) {
        registry.counter("ingestion.pdf.retention.deleted").increment(count);
    }
}
