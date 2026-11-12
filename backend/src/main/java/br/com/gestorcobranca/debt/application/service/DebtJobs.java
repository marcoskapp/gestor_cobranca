package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.DebtProperties;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import io.micrometer.core.instrument.Timer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
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
    static final String JOB_DOCUMENT_ROOT_BACKFILL = "debt.document_root_backfill";
    static final String JOB_CONTACT_NOVELTY_SWEEP = "debt.contact_novelty_sweep";
    private static final Logger LOG = LoggerFactory.getLogger(DebtJobs.class);

    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final PrescriptionAlertService prescriptionAlerts;
    private final TitleAmountAdjustmentService amountAdjustments;
    private final DocumentRootBackfill documentRoots;
    private final DebtMetrics metrics;
    private final ContactNoveltyService contactNovelty;
    private final DebtProperties properties;
    private final Clock clock;

    DebtJobs(TenantDirectory tenantDirectory, TenantExecution tenantExecution, PrescriptionAlertService prescriptionAlerts,
             TitleAmountAdjustmentService amountAdjustments, DocumentRootBackfill documentRoots, DebtMetrics metrics,
             ContactNoveltyService contactNovelty, DebtProperties properties, Clock clock) {
        this.contactNovelty = contactNovelty;
        this.properties = properties;
        this.clock = clock;
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.prescriptionAlerts = prescriptionAlerts;
        this.amountAdjustments = amountAdjustments;
        this.documentRoots = documentRoots;
        this.metrics = metrics;
    }

    /**
     * Raiz do CNPJ (matriz e filiais) dos cadastros de empresa anteriores a ela, em lotes por assessoria. Depois do
     * primeiro ciclo não há o que preencher (a carga e o cadastro já gravam a raiz) e a varredura termina na primeira
     * consulta.
     */
    @Scheduled(cron = "${app.debt.document-root-backfill-cron:0 40 4 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "debt.document-root-backfill", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    void documentRootBackfill() {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                int filled = 0;
                int batch;
                do {
                    batch = tenantExecution.callInTenant(tenantId, JOB_DOCUMENT_ROOT_BACKFILL, documentRoots::backfillBatch);
                    filled += batch;
                } while (batch == DocumentRootBackfill.BATCH);
                if (filled > 0) {
                    LOG.atInfo().addKeyValue("tenantId", tenantId).addKeyValue("debtors", filled)
                            .log("Raiz do CNPJ preenchida em cadastros de empresa anteriores");
                }
            } catch (RuntimeException e) {
                metrics.jobFailure(JOB_DOCUMENT_ROOT_BACKFILL);
                LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("job", JOB_DOCUMENT_ROOT_BACKFILL).setCause(e)
                        .log("Falha no preenchimento da raiz do CNPJ");
            }
        }
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

    /**
     * Fim da novidade dos contatos recebidos na carga cuja janela venceu sem tentativa
     * (docs/tecnico/desenho/contatos-recebidos.md §3): republica a novidade de cada devedor, para o collection refazer o
     * roteamento e o campaign atualizar a audiência. Um lote por transação, com o tenant definido em cada rodada.
     */
    @Scheduled(cron = "${app.debt.contact-novelty.sweep-cron:0 40 3 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "debt.contact-novelty-sweep", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    void contactNoveltySweep() {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            sweepContactNovelty(tenantId);
        }
    }

    /** @return devedores com a novidade republicada na assessoria (-1 em falha) */
    int sweepContactNovelty(UUID tenantId) {
        Instant from = Instant.now(clock).minus(properties.contactNovelty().sweepLookback());
        int published = 0;
        try {
            UUID cursor = null;
            do {
                UUID after = cursor;
                ContactNoveltyService.SweepBatch batch = tenantExecution.callInTenant(tenantId, JOB_CONTACT_NOVELTY_SWEEP,
                        () -> contactNovelty.sweepBatch(from, after));
                published += batch.published();
                cursor = batch.cursor();
            } while (cursor != null);
            LOG.atInfo().addKeyValue("tenantId", tenantId).addKeyValue("debtors", published)
                    .log("Varredura da novidade dos contatos recebidos concluída");
            return published;
        } catch (RuntimeException e) {
            metrics.jobFailure(JOB_CONTACT_NOVELTY_SWEEP);
            metrics.noveltySweep("failed", 1);
            LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("job", JOB_CONTACT_NOVELTY_SWEEP).setCause(e)
                    .log("Falha na varredura da novidade dos contatos recebidos");
            return -1;
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
