package br.com.gestorcobranca.campaign.application.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Métricas do módulo (Micrometer → CloudWatch). Nomes e tags são contrato com painéis e alarmes,
 * documentados em {@code docs/tecnico/logs-e-metricas.md} §5 — não renomear. Tags só com valores de
 * baixa cardinalidade (enums, códigos estáveis); nunca ids.
 */
@Component
class CampaignMetrics {

    private final MeterRegistry registry;

    CampaignMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** @param outcome enrolled, holdout, suppressed_concurrency, preempted, skipped_blocked, already_enrolled */
    void enrollment(String outcome) {
        registry.counter("campaign.enrollments", "outcome", outcome).increment();
    }

    /** @param outcome executed, skipped, failed, deferred */
    void step(String action, String channel, String outcome) {
        registry.counter("campaign.steps", "action", action, "channel", channel == null ? "none" : channel,
                "outcome", outcome).increment();
    }

    void stepSkipped(String reason) {
        registry.counter("campaign.steps.skipped", "reason", reason).increment();
    }

    void stepFailed(String code) {
        registry.counter("campaign.steps.failed", "code", code).increment();
    }

    void stepDuration(String action, Duration duration) {
        Timer.builder("campaign.step.duration").tag("action", action).publishPercentiles(0.5, 0.95, 0.99)
                .register(registry).record(duration);
    }

    /** @param reason motivo da pausa; {@code scope} enrollment ou campaign */
    void pause(String scope, String reason) {
        registry.counter("campaign.pauses", "scope", scope, "reason", reason).increment();
    }

    void resume(String trigger) {
        registry.counter("campaign.resumes", "trigger", trigger).increment();
    }

    void ended(String reason) {
        registry.counter("campaign.enrollments.ended", "reason", reason.toLowerCase(Locale.ROOT)).increment();
    }

    void reply(String transition) {
        registry.counter("campaign.replies", "transition", transition).increment();
    }

    void attribution(String kind) {
        registry.counter("campaign.attributions", "kind", kind).increment();
    }

    /** @param outcome updated, missing, closed */
    void audienceRefresh(String trigger, String outcome) {
        registry.counter("campaign.audience.refreshes", "trigger", trigger, "outcome", outcome).increment();
    }

    void segmentEvaluation(String operation, Duration duration) {
        Timer.builder("campaign.segment.evaluation").tag("operation", operation).publishPercentiles(0.5, 0.95, 0.99)
                .register(registry).record(duration);
    }

    void experimentAssignment(boolean control) {
        registry.counter("campaign.experiment.assignments", "arm", control ? "control" : "variant").increment();
    }

    void cadenceExhausted(String purpose) {
        registry.counter("campaign.cadence.exhausted", "purpose", purpose).increment();
    }

    void jobFailure(String job) {
        registry.counter("campaign.jobs.failures", "job", job).increment();
    }

    void eventConsumed(String type) {
        registry.counter("campaign.events.consumed", "type", type).increment();
    }
}
