package br.com.gestorcobranca.debt.application.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /**
     * Identificadores trocados pela forma anonimizada (decisão 1476): {@code kind} título ou contrato, {@code origin}
     * eliminação ou preenchimento dos já anonimizados. Sem credor nem assessoria; contado depois do commit.
     */
    void externalIdsAnonymized(String kind, String origin, int count) {
        if (count <= 0) {
            return;
        }
        Runnable inc = () -> registry.counter("debt.external_ids.anonymized", "kind", kind, "origin", origin)
                .increment(count);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            inc.run();
                        }
                    });
        } else {
            inc.run();
        }
    }

    /**
     * Consulta da aba "Pela carga do credor" da lista "Baixas informadas pelo credor" (H07). Só tags de baixa
     * cardinalidade (RS-103).
     *
     * @param situation SETTLED_BY_IMPORT, REPORTED_PAID, REPORTED_CANCELLED ou ALL
     * @param outcome   ok, empty
     */
    void creditorReportedQuery(String situation, String outcome) {
        registry.counter("debt.creditor_reported.queries", "tab", "imports", "situation", situation, "outcome",
                outcome).increment();
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

    /** @param outcome requested, applied, rejected, cancelled; @param mode WITHIN_LIMIT ou DUAL_CONTROL */
    void amountAdjustment(String outcome, String mode) {
        registry.counter("debt.title_amount.adjustments", "outcome", outcome, "mode", mode).increment();
    }

    /** Redução que caberia sozinha no limite, mas foi para aprovação pelo limite acumulado do título. */
    void amountAdjustmentCumulativeLimit() {
        registry.counter("debt.title_amount.cumulative_limit").increment();
    }

    /** Proposta de aumento do limite descartada por vencer sem confirmação. */
    void thresholdProposalExpired() {
        registry.counter("debt.title_amount.threshold_proposals_expired").increment();
    }

    /**
     * Contato recebido numa carga: {@code new} = contato novo para devedor já existente (abre a janela de novidade),
     * {@code resent} = reenvio de contato que o devedor já tinha.
     */
    void contactReceived(String kind) {
        registry.counter("debt.contacts.received", "kind", kind).increment();
    }

    /** Uso de contato ignorado pelo consumidor (contact_mismatch, contact_not_found). */
    void contactUsageIgnored(String reason) {
        registry.counter("debt.contacts.usage_ignored", "reason", reason).increment();
    }

    /** @param outcome published (novidade do devedor republicada após janela vencida), failed (assessoria com falha) */
    void noveltySweep(String outcome, int count) {
        if (count > 0) {
            registry.counter("debt.contacts.novelty_sweep", "outcome", outcome).increment(count);
        }
    }

    /** @param outcome detected, seen_again, KEPT_MANUAL, ACCEPTED_CREDITOR, RESOLVED_BY_CREDITOR */
    void amountDivergence(String outcome) {
        registry.counter("debt.title_amount.divergences", "outcome", outcome).increment();
    }
}
