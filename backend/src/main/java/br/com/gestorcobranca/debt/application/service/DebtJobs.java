package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import io.micrometer.core.instrument.Timer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Agendamentos do módulo (uma instância por vez, via ShedLock), percorrendo as assessorias
 * ativas. A falha num tenant é registrada (ERROR + {@code debt.jobs.failures}) e não impede os
 * demais; a próxima execução retoma o que faltou (reconciliação e alerta são idempotentes).
 */
@Component
class DebtJobs {

    static final String JOB_PRESCRIPTION_DAILY = "prescription_daily";
    static final String JOB_THRESHOLD_PROPOSAL_EXPIRY = "debt.amount_threshold_proposal_expiry";
    private static final Logger LOG = LoggerFactory.getLogger(DebtJobs.class);

    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final PrescriptionAlertService prescriptionAlerts;
    private final TitleAmountAdjustmentService amountAdjustments;
    private final DebtMetrics metrics;

    DebtJobs(TenantDirectory tenantDirectory, TenantExecution tenantExecution, PrescriptionAlertService prescriptionAlerts,
             TitleAmountAdjustmentService amountAdjustments, DebtMetrics metrics) {
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.prescriptionAlerts = prescriptionAlerts;
        this.amountAdjustments = amountAdjustments;
        this.metrics = metrics;
    }

    /** Reconciliação e alerta de prescrição próxima, de madrugada/início da manhã no fuso de operação. */
    @Scheduled(cron = "${app.debt.prescription.daily-cron:0 10 6 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "debt.prescription-daily", lockAtMostFor = "PT3H", lockAtLeastFor = "PT1M")
    void prescriptionDaily() {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                runPrescriptionDaily(tenantId);
            } catch (RuntimeException e) {
                metrics.jobFailure(JOB_PRESCRIPTION_DAILY);
                LOG.atError().addKeyValue("tenantId", tenantId).setCause(e)
                        .log("Falha na varredura diária de prescrição");
            }
        }
    }

    /**
     * Descarte das propostas de aumento do limite da correção de valor vencidas sem confirmação (decisão do responsável
     * sobre a 865), uma transação por assessoria (uma linha de configuração por assessoria).
     */
    @Scheduled(cron = "${app.debt.amount-adjustment.proposal-expiry-cron:0 50 3 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "debt.amount-threshold-proposal-expiry", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void expireThresholdProposals() {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            expireThresholdProposal(tenantId);
        }
    }

    /** @return {@code true} se a assessoria tinha proposta vencida e ela foi descartada */
    boolean expireThresholdProposal(UUID tenantId) {
        try {
            return tenantExecution.callInTenant(tenantId, JOB_THRESHOLD_PROPOSAL_EXPIRY,
                    amountAdjustments::discardExpiredProposal);
        } catch (RuntimeException e) {
            metrics.jobFailure(JOB_THRESHOLD_PROPOSAL_EXPIRY);
            LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("job", JOB_THRESHOLD_PROPOSAL_EXPIRY).setCause(e)
                    .log("Falha no descarte da proposta de aumento do limite vencida");
            return false;
        }
    }

    void runPrescriptionDaily(UUID tenantId) {
        int batch = prescriptionAlerts.batchSize();
        Timer.Sample reconcile = metrics.startTimer();
        int reconciled = 0;
        int processed;
        do {
            processed = tenantExecution.callInTenant(tenantId, "debt.prescription_reconcile", prescriptionAlerts::reconcileBatch);
            reconciled += processed;
        } while (processed == batch);
        metrics.stopScan(reconcile, "reconcile");

        Timer.Sample alert = metrics.startTimer();
        int alerted = 0;
        do {
            processed = tenantExecution.callInTenant(tenantId, "debt.prescription_alert", prescriptionAlerts::alertBatch);
            alerted += processed;
        } while (processed == batch);
        metrics.stopScan(alert, "alert");
        LOG.atInfo().addKeyValue("tenantId", tenantId).addKeyValue("contractsReconciled", reconciled)
                .addKeyValue("alerts", alerted).log("Varredura diária de prescrição concluída");
    }
}
