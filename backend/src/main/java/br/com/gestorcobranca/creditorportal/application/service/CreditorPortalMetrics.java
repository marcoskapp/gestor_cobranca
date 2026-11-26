package br.com.gestorcobranca.creditorportal.application.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Métricas técnicas das leituras do Início e da atividade do portal do credor (fatia 7; logs-e-metricas.md §3.16).
 * Tags de cardinalidade fixa — nunca assessoria, credor ou pessoa. Acima de 2 s, WARN só com a duração.
 */
@Component
class CreditorPortalMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(CreditorPortalMetrics.class);
    static final Duration SLOW = Duration.ofSeconds(2);

    private final MeterRegistry registry;

    CreditorPortalMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** @param cache {@code hit}, {@code miss} ou {@code none} (indicadores fora do ar) */
    void overview(Duration elapsed, String cache) {
        Timer.builder("creditor_portal.overview.duration").tag("cache", cache).publishPercentiles(0.5, 0.95, 0.99)
                .register(registry).record(elapsed);
        if (elapsed.compareTo(SLOW) > 0) {
            LOG.atWarn().addKeyValue("durationMs", elapsed.toMillis()).log("Início do portal do credor lento");
        }
    }

    /** @param scope {@code own} ou {@code team} */
    void activity(String scope) {
        Counter.builder("creditor_portal.activity.requests").tag("scope", scope).register(registry).increment();
    }

    /** Fonte do Início fora do ar nesta resposta (a seção fica indisponível). */
    void sourceUnavailable(String source) {
        Counter.builder("creditor_portal.sources.unavailable").tag("source", source).register(registry).increment();
    }
}
