package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.out.WebhookReceiptLog;
import br.com.gestorcobranca.payment.domain.model.PaymentInstruction;
import br.com.gestorcobranca.payment.domain.repository.PaymentInstructionRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Agendamentos do módulo (uma instância por vez, via ShedLock), percorrendo as assessorias ativas:
 * expiração de instruções vencidas e descarte de recibos de webhook antigos. A falha numa assessoria é
 * registrada (alarme {@code payment.jobs.failures}) e não impede as demais.
 */
@Component
class PaymentJobs {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentJobs.class);
    private static final int BATCH = 500;
    private static final int MAX_BATCHES = 100;

    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final PaymentInstructionRepository instructions;
    private final WebhookReceiptLog receipts;
    private final AuditTrail auditTrail;
    private final PaymentMetrics metrics;
    private final PaymentProperties properties;
    private final Clock clock;

    PaymentJobs(TenantDirectory tenantDirectory, TenantExecution tenantExecution, PaymentInstructionRepository instructions,
                WebhookReceiptLog receipts, AuditTrail auditTrail, PaymentMetrics metrics, PaymentProperties properties,
                Clock clock) {
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.instructions = instructions;
        this.receipts = receipts;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.payment.expiration-check-interval:PT15M}")
    @SchedulerLock(name = "payment.expiration", lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
    void expireInstructions() {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                int batches = 0;
                int expired;
                do {
                    expired = tenantExecution.callInTenant(tenantId, "payment.expiration", this::expireBatch);
                    batches++;
                } while (expired == BATCH && batches < MAX_BATCHES);
            } catch (RuntimeException e) {
                metrics.jobFailure("expiration");
                LOG.atError().addKeyValue("tenantId", tenantId).setCause(e).log("Falha na expiração de instruções");
            }
        }
    }

    /** @return instruções expiradas neste lote (tenant corrente) */
    int expireBatch() {
        Instant now = Instant.now(clock);
        List<PaymentInstruction> due = instructions.findOpenExpiredBefore(now, BATCH);
        for (PaymentInstruction instruction : due) {
            if (instruction.expireIfDue(now)) {
                instructions.save(instruction);
                metrics.instruction(instruction.getType().name(), "expired");
                auditTrail.record(AuditEntry.of("payment_instruction.expired", InstructionService.RESOURCE,
                        instruction.getId()).creditor(instruction.getCreditorId()).meta("expiresAt", instruction.getExpiresAt()));
            }
        }
        return due.size();
    }

    @Scheduled(cron = "0 50 2 * * *", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "payment.webhook_receipts", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    void purgeWebhookReceipts() {
        Instant cutoff = Instant.now(clock).minus(properties.webhook().maxEventAge());
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                tenantExecution.callInTenant(tenantId, "payment.webhook_receipts", () -> receipts.purgeBefore(cutoff));
            } catch (RuntimeException e) {
                metrics.jobFailure("webhook_receipts");
                LOG.atError().addKeyValue("tenantId", tenantId).setCause(e).log("Falha no descarte de recibos de webhook");
            }
        }
    }
}
