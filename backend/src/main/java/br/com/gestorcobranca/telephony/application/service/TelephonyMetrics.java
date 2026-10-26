package br.com.gestorcobranca.telephony.application.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Métricas do módulo (Micrometer → CloudWatch). Nomes e tags são contrato com painéis e alarmes,
 * documentados em {@code docs/tecnico/logs-e-metricas.md} §5 — não renomear. Tags só com valores de
 * baixa cardinalidade (enums, códigos estáveis); nunca ids, números ou ramais.
 * <p>
 * Indicadores derivados no CloudWatch (metric math): <b>taxa de abandono</b> =
 * {@code telephony.dialer.live{outcome=abandoned}} ÷ soma de {@code telephony.dialer.live};
 * <b>ASR</b> = {@code telephony.calls{result=ANSWERED|VOICEMAIL|ABANDONED}} ÷ {@code telephony.calls}
 * (somente discadas).
 */
@Component
class TelephonyMetrics {

    private final MeterRegistry registry;

    TelephonyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Chamada discada/recebida encerrada, por resultado consolidado. */
    void callEnded(String direction, String origin, String result) {
        registry.counter("telephony.calls", "direction", direction, "origin", origin, "result", result).increment();
    }

    /**
     * Comparação entre provedores: chamada encerrada por provedor e resultado (ASR por provedor) e
     * duração da conversação. Série própria para não mudar as dimensões de {@code telephony.calls}
     * (painéis e alarmes existentes).
     */
    void providerCallEnded(String provider, String origin, String result, Integer talkSeconds) {
        registry.counter("telephony.provider.calls", "provider", provider, "origin", origin, "result", result)
                .increment();
        if (talkSeconds != null && talkSeconds > 0) {
            Timer.builder("telephony.provider.talk_time").tag("provider", provider)
                    .publishPercentiles(0.5, 0.95, 0.99).register(registry).record(Duration.ofSeconds(talkSeconds));
        }
    }

    /** Pedido ao provedor por resultado: accepted, rejected, transient, transient_not_processed. */
    void providerDial(String provider, String origin, String outcome) {
        registry.counter("telephony.provider.dial", "provider", provider, "origin", origin, "outcome", outcome)
                .increment();
    }

    /** Discagem desviada para a conta de contingência (de → para), por origem. */
    void fallback(String fromProvider, String toProvider, String origin, int calls) {
        registry.counter("telephony.provider.fallbacks", "from", fromProvider, "to", toProvider, "origin", origin)
                .increment(calls);
    }

    /** Pedido de discagem: accepted, rejected, transient, blocked, account_unavailable, exhausted. */
    void dial(String origin, String outcome) {
        registry.counter("telephony.dial.requests", "origin", origin, "outcome", outcome).increment();
    }

    /** Negação do porteiro; um incremento por motivo. */
    void blocked(String origin, String stage, String reason) {
        registry.counter("telephony.calls.blocked", "origin", origin, "stage", stage, "reason", reason).increment();
    }

    /** Atendidas por pessoa no discador: connected ou abandoned (base da taxa de abandono). */
    void live(String mode, String outcome) {
        registry.counter("telephony.dialer.live", "mode", mode, "outcome", outcome).increment();
    }

    /** Decisão do ritmo por ciclo (motivo e quantidade de chamadas colocadas). */
    void pacing(String mode, String reason, int placed) {
        registry.counter("telephony.dialer.ticks", "mode", mode, "reason", reason).increment();
        if (placed > 0) {
            registry.counter("telephony.dialer.placed", "mode", mode).increment(placed);
        }
    }

    /** Itens da lista: loaded, deferred, excluded_<motivo>, completed, exhausted, retry. */
    void entry(String outcome) {
        registry.counter("telephony.dialer.entries", "outcome", outcome).increment();
    }

    void ringTime(String origin, int seconds) {
        timer("telephony.call.ring_time", origin).record(Duration.ofSeconds(seconds));
    }

    void talkTime(String origin, int seconds) {
        timer("telephony.call.talk_time", origin).record(Duration.ofSeconds(seconds));
    }

    void wrapUpTime(String origin, int seconds) {
        timer("telephony.call.wrap_up_time", origin).record(Duration.ofSeconds(seconds));
    }

    /** Do atendimento à conexão com o operador (distribuição). */
    void distributionLatency(String origin, Duration duration) {
        timer("telephony.call.distribution_time", origin).record(duration);
    }

    /** Atendida e encerrada pelo nosso lado em até 3 s ("chamada curta", acompanhamento Anatel). */
    void shortCall(String origin) {
        registry.counter("telephony.calls.short", "origin", origin).increment();
    }

    /** Mudança de presença por estado de destino e motivo de pausa. */
    void presence(String to, String reason) {
        registry.counter("telephony.presence.transitions", "to", to, "reason", reason == null ? "none" : reason)
                .increment();
    }

    /** @param mode operator, automatic (sem operador) ou deadline (prazo do pós-atendimento vencido) */
    void tabulation(String mode) {
        registry.counter("telephony.tabulations", "mode", mode).increment();
    }

    /** Registro no caso: recorded, duplicate, skipped_* . */
    void interaction(String outcome) {
        registry.counter("telephony.interactions", "outcome", outcome).increment();
    }

    /** @param outcome stored, failed, purged_retention, purged_anonymization, too_large */
    void recording(String outcome) {
        registry.counter("telephony.recordings", "outcome", outcome).increment();
    }

    void recordingAccess(String purpose) {
        registry.counter("telephony.recording.access", "purpose", purpose).increment();
    }

    /** @param outcome ACCEPTED, DUPLICATE, UNKNOWN_ROUTE, INVALID_SIGNATURE, MALFORMED, TOO_LARGE, RATE_LIMITED */
    void webhookRequest(String provider, String outcome) {
        registry.counter("telephony.webhook.requests", "provider", provider, "outcome", outcome).increment();
    }

    /** @param outcome applied, duplicate, stale, unknown_call, ignored */
    void webhookEvent(String provider, String type, String outcome) {
        registry.counter("telephony.webhook.events", "provider", provider, "type", type, "outcome", outcome)
                .increment();
    }

    void eventConsumed(String type) {
        registry.counter("telephony.events.consumed", "type", type).increment();
    }

    void jobFailure(String job) {
        registry.counter("telephony.jobs.failures", "job", job).increment();
    }

    /** Conciliação: chamadas sem evento do provedor encerradas; operadores sem sinal desconectados. */
    void reconciled(String kind, int count) {
        if (count > 0) {
            registry.counter("telephony.reconciliation", "kind", kind).increment(count);
        }
    }

    private Timer timer(String name, String origin) {
        return Timer.builder(name).tag("origin", origin).publishPercentiles(0.5, 0.95, 0.99).register(registry);
    }
}
