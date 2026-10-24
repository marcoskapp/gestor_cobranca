package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.domain.model.BlockTally;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatch;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Métricas {@code ingestion.*} (catálogo em {@code docs/tecnico/logs-e-metricas.md}). Tags apenas com valores de
 * baixa cardinalidade (origem, modo, status, resultado) — nunca ids.
 */
@Component
class IngestionMetrics {

    private final MeterRegistry registry;

    IngestionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void received(ImportBatch batch) {
        registry.counter("ingestion.batches.received", "source", batch.getSource().name(), "mode", batch.getMode().name())
                .increment();
    }

    /** Arquivo/requisição recusado no recebimento ({@code reason} = código estável do erro). */
    void rejected(String reason) {
        registry.counter("ingestion.files.rejected", "reason", reason).increment();
    }

    void finished(ImportBatch batch) {
        String status = batch.getStatus().name();
        registry.counter("ingestion.batches.finished", "source", batch.getSource().name(), "mode",
                batch.getMode().name(), "status", status).increment();
        if (batch.getStartedAt() != null && batch.getFinishedAt() != null) {
            Timer.builder("ingestion.batch.duration")
                    .tags("source", batch.getSource().name(), "mode", batch.getMode().name(), "status", status)
                    .publishPercentiles(0.5, 0.95)
                    .register(registry)
                    .record(Duration.between(batch.getStartedAt(), batch.getFinishedAt()));
        }
    }

    void block(String mode, BlockTally tally, int issuesSaved) {
        registry.counter("ingestion.rows", "mode", mode, "outcome", "valid").increment(tally.rowsValid());
        registry.counter("ingestion.rows", "mode", mode, "outcome", "rejected").increment(tally.rowsWithError());
        registry.counter("ingestion.rows", "mode", mode, "outcome", "excluded").increment(tally.rowsExcluded());
        registry.counter("ingestion.titles", "outcome", "created").increment(tally.titlesCreated());
        registry.counter("ingestion.titles", "outcome", "updated").increment(tally.titlesUpdated());
        registry.counter("ingestion.titles", "outcome", "unchanged").increment(tally.titlesUnchanged());
        registry.counter("ingestion.titles", "outcome", "skipped").increment(tally.titlesSkipped());
        registry.counter("ingestion.titles", "outcome", "suspended").increment(tally.titlesSuspended());
        registry.counter("ingestion.issues", "severity", "ERROR").increment(tally.errors());
        registry.counter("ingestion.issues", "severity", "WARNING").increment(tally.warnings());
        registry.counter("ingestion.issues.stored").increment(issuesSaved);
    }

    Timer.Sample start() {
        return Timer.start(registry);
    }

    /** {@code outcome}: continued (há próxima fatia), finished, failed, cancelled ou error (nova tentativa). */
    void slice(Timer.Sample sample, String mode, String outcome) {
        sample.stop(Timer.builder("ingestion.slice.duration").tags("mode", mode, "outcome", outcome)
                .register(registry));
    }

    /** Tempo de gravação de uma linha no módulo debt ({@code outcome}: written, skipped, rejected). */
    void rowWrite(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder("ingestion.row.write.duration").tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95, 0.99).register(registry));
    }

    /** Situação do formato numérico de cada coluna monetária analisada na leitura prévia. */
    void formatColumn(String status) {
        registry.counter("ingestion.format.columns", "status", status).increment();
    }

    /** Valores monetários em texto (no XLSX: "números armazenados como texto"). */
    void textNumbers(String source, long count) {
        registry.counter("ingestion.format.text_numbers", "source", source).increment(count);
    }

    /**
     * Conferência de totais ({@code result}: match, mismatch; {@code hint}: scale, alternative, none). Taxa de
     * divergência = mismatch ÷ total.
     */
    void totalsCheck(String origin, boolean matched, String hint) {
        registry.counter("ingestion.totals.checks", "origin", origin, "result", matched ? "match" : "mismatch",
                "hint", hint).increment();
    }

    void alertsDetected(String type, long count) {
        registry.counter("ingestion.review.alerts", "type", type).increment(count);
    }

    void alertsDecided(String decision, long count) {
        registry.counter("ingestion.review.decisions", "decision", decision).increment(count);
    }

    void reviewCommitted(boolean approvalRequired) {
        registry.counter("ingestion.review.commits", "approval", approvalRequired ? "required" : "not_required")
                .increment();
    }

    void reviewApproval(String decision) {
        registry.counter("ingestion.review.approvals", "decision", decision).increment();
    }

    void analysis(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder("ingestion.review.analysis.duration").tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95).register(registry));
    }

    /** Linha regravada após conflito de unicidade com outra carga simultânea. */
    void rowRetried() {
        registry.counter("ingestion.rows.retried").increment();
    }
}
