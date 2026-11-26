package br.com.gestorcobranca.ingestion.application.service;

import org.springframework.context.ApplicationContext;

import java.util.UUID;

/**
 * Acesso dos testes de integração às tarefas periódicas do ingestion (o agendamento fica desligado nos testes). Os
 * métodos abrem a própria transação no tenant, como o job.
 */
public final class IngestionTestHooks {

    private IngestionTestHooks() {
    }

    /** Descarte dos arquivos de importação pela política IMPORT_FILES (job {@code ingestion.file-retention}). */
    public static int purgeImportFiles(ApplicationContext context, UUID tenantId) {
        return context.getBean(ImportFileRetention.class).runFor(tenantId);
    }

    /** Anonimização do titular no ingestion (consumidor {@code ingestion.debtor-anonymized}), síncrona, no tenant. */
    public static void anonymizeDebtor(ApplicationContext context, UUID tenantId, UUID debtorId) {
        anonymizeDebtor(context, tenantId, debtorId, br.com.gestorcobranca.shared.domain.Ids.newId());
    }

    /** Idem, com o id do evento (reentrega do mesmo evento). */
    public static void anonymizeDebtor(ApplicationContext context, UUID tenantId, UUID debtorId, UUID eventId) {
        context.getBean(br.com.gestorcobranca.shared.tenancy.TenantExecution.class).inTenant(tenantId,
                "test.ingestion-anonymize", () -> context.getBean(IngestionAnonymizer.class).anonymize(debtorId, eventId));
    }

    /** Job de uso único {@code ingestion.pending-erasure}, como o {@code JobWorker} (sem transação externa). */
    public static void runPendingErasureJob(ApplicationContext context, UUID tenantId, UUID batchId) {
        context.getBean(br.com.gestorcobranca.shared.tenancy.TenantExecution.class).withTenantContext(tenantId,
                "job:" + PendingErasureJobHandler.TYPE, () -> context.getBean(PendingErasureJobHandler.class).handle(
                        new br.com.gestorcobranca.shared.jobs.JobContext(br.com.gestorcobranca.shared.domain.Ids.newId(),
                                tenantId, PendingErasureJobHandler.TYPE, 1),
                        new PendingErasureJobHandler.Payload(batchId)));
    }
}
