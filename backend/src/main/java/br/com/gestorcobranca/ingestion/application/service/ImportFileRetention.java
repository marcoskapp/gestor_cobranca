package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases;
import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases.AppliedRetention;
import br.com.gestorcobranca.ingestion.application.port.out.ImportFileRetentionStore;
import br.com.gestorcobranca.ingestion.application.port.out.ImportFileRetentionStore.ExpiredFile;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.storage.FileStorage;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Descarte dos arquivos de importação (CSV, XLSX, registros da API e o CSV canônico gerado do PDF) pela política
 * {@code IMPORT_FILES} do compliance (LGPD, arts. 15 e 16), que nasce desabilitada: arquivos cujos lotes estão todos
 * encerrados há mais de N meses e sem conferência aberta saem do armazenamento; o lote fica (contadores, hash,
 * ocorrências mascaradas e trilha) com {@code file_deleted_at}. Um {@code AuditEntry import_batch.file_deleted} por
 * arquivo. Rodadas de {@link #BATCH} arquivos, cada uma na própria transação, até esvaziar (teto de
 * {@link #MAX_ROUNDS}). Uma instância por vez.
 */
@Component
class ImportFileRetention {

    static final String RETENTION_CATEGORY = "IMPORT_FILES";
    static final int BATCH = 200;
    static final int MAX_ROUNDS = 50;
    private static final Logger LOG = LoggerFactory.getLogger(ImportFileRetention.class);
    private static final String JOB = "ingestion.file-retention";

    private final TenantDirectory tenants;
    private final TenantExecution tenantExecution;
    private final RetentionUseCases retention;
    private final ImportFileRetentionStore store;
    private final FileStorage storage;
    private final AuditTrail auditTrail;
    private final IngestionMetrics metrics;
    private final Clock clock;

    ImportFileRetention(TenantDirectory tenants, TenantExecution tenantExecution, RetentionUseCases retention,
                        ImportFileRetentionStore store, FileStorage storage, AuditTrail auditTrail,
                        IngestionMetrics metrics, Clock clock) {
        this.tenants = tenants;
        this.tenantExecution = tenantExecution;
        this.retention = retention;
        this.store = store;
        this.storage = storage;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.ingestion.file-retention-cron:0 40 3 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = JOB, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void retention() {
        for (UUID tenantId : tenants.activeTenantIds()) {
            runFor(tenantId);
        }
    }

    /** Uma assessoria (também usado pelos testes). @return arquivos descartados */
    int runFor(UUID tenantId) {
        int total = 0;
        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                Round r = tenantExecution.callInTenant(tenantId, JOB, this::purgeRound);
                total += r.deleted();
                if (r.candidates() < BATCH) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("job", JOB).setCause(e)
                    .log("Falha no descarte dos arquivos de importação");
        }
        metrics.filesDeleted(total);
        if (total > 0) {
            LOG.atInfo().addKeyValue("tenantId", tenantId).addKeyValue("deleted", total)
                    .log("Descarte dos arquivos de importação executado");
        }
        return total;
    }

    /** @param candidates arquivos selecionados (rodada cheia = pode haver mais) */
    private record Round(int candidates, int deleted) {
    }

    private Round purgeRound() {
        Optional<AppliedRetention> policy = retention.retentionFor(RETENTION_CATEGORY);
        if (policy.isEmpty()) {
            return new Round(0, 0);
        }
        Instant now = clock.instant();
        Instant cutoff = policy.get().cutoff(now);
        List<ExpiredFile> files = store.expiredFiles(cutoff, BATCH);
        int deleted = 0;
        for (ExpiredFile f : files) {
            int marked = store.markDeleted(f.fileKey(), now);
            if (marked == 0) {
                continue;
            }
            storage.delete(f.fileKey());
            auditTrail.record(AuditEntry.of("import_batch.file_deleted", BatchCreation.RESOURCE, f.batchId())
                    .creditor(f.creditorId()).meta("batches", marked).meta("fileSha256", f.fileSha256())
                    .reason("política IMPORT_FILES de " + policy.get().describe()));
            deleted++;
        }
        return new Round(files.size(), deleted);
    }
}
