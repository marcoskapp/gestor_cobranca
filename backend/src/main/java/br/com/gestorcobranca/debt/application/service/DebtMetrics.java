package br.com.gestorcobranca.debt.application.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Métricas Micrometer das garantias, partes da obrigação e prescrição (documentadas em
 * {@code docs/tecnico/logs-e-metricas.md} §5). Tags com cardinalidade fixa (enums); nunca ids.
 */
@Component
class DebtMetrics {

    private final MeterRegistry registry;

    DebtMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void collateralRegistered(Object type, String source) {
        registry.counter("debt.collateral.registered", "type", String.valueOf(type), "source", source).increment();
    }

    void collateralTransition(Object type, Object to) {
        registry.counter("debt.collateral.transitions", "type", String.valueOf(type), "to", String.valueOf(to)).increment();
    }

    void collateralAppraised(Object source) {
        registry.counter("debt.collateral.appraisals", "source", String.valueOf(source)).increment();
    }

    void obligationPartyRegistered(Object role, String source) {
        registry.counter("debt.obligation_parties.registered", "role", String.valueOf(role), "source", source).increment();
    }

    void interruptionRegistered(Object type, Object origin) {
        registry.counter("debt.prescription.interruptions", "type", String.valueOf(type), "origin", String.valueOf(origin))
                .increment();
    }

    /** Linhas de prescrição criadas, alteradas ou removidas por recálculo. */
    void prescriptionsRecalculated(String trigger, int count) {
        if (count > 0) {
            registry.counter("debt.prescription.recalculated", "trigger", trigger).increment(count);
        }
    }

    void prescriptionAlert(Object claim, Object instrumentType) {
        registry.counter("debt.prescription.alerts", "claim", String.valueOf(claim), "instrumentType",
                String.valueOf(instrumentType)).increment();
    }

    void jobFailure(String job) {
        registry.counter("debt.jobs.failures", "job", job).increment();
    }

    Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    void stopScan(Timer.Sample sample, String phase) {
        sample.stop(registry.timer("debt.prescription.scan.duration", "phase", phase));
    }
}
