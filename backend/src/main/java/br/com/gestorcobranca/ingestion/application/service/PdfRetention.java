package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.PdfImportProperties;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImport;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImportStatus;
import br.com.gestorcobranca.ingestion.domain.repository.PdfImportRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Retenção dos PDFs ({@code app.ingestion.pdf.retention}, padrão 120 dias): importações aprovadas, descartadas ou com
 * falha há mais tempo têm o arquivo apagado do armazenamento e a leitura visual removida; ficam o hash, o mapa, o
 * resumo e a trilha. O lote canônico gerado segue a retenção dos arquivos de importação. Uma instância por vez.
 */
@Component
class PdfRetention {

    private static final Logger LOG = LoggerFactory.getLogger(PdfRetention.class);
    private static final List<PdfImportStatus> CLOSED = List.of(PdfImportStatus.APPROVED, PdfImportStatus.DISCARDED,
            PdfImportStatus.FAILED);

    private final TenantDirectory tenants;
    private final TenantExecution tenantExecution;
    private final PdfImportRepository imports;
    private final FileStorage storage;
    private final AuditTrail auditTrail;
    private final PdfImportMetrics metrics;
    private final PdfImportProperties properties;
    private final Clock clock;

    PdfRetention(TenantDirectory tenants, TenantExecution tenantExecution, PdfImportRepository imports,
                 FileStorage storage, AuditTrail auditTrail, PdfImportMetrics metrics, PdfImportProperties properties,
                 Clock clock) {
        this.tenants = tenants;
        this.tenantExecution = tenantExecution;
        this.imports = imports;
        this.storage = storage;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.ingestion.pdf.retention-cron:0 20 3 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "ingestion.pdf-retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void retention() {
        for (UUID tenantId : tenants.activeTenantIds()) {
            runFor(tenantId);
        }
    }

    /** Uma assessoria (também usado pelos testes). */
    int runFor(UUID tenantId) {
        Instant before = clock.instant().minus(properties.retention());
        try {
            int deleted = tenantExecution.callInTenant(tenantId, "ingestion.pdf-retention", () -> {
                int count = 0;
                for (PdfImport pdf : imports.findRetentionCandidates(CLOSED, before,
                        PageRequest.of(0, properties.retentionBatch()))) {
                    String key = pdf.getFileKey();
                    pdf.fileDeleted(clock.instant());
                    imports.save(pdf);
                    storage.delete(key);
                    auditTrail.record(AuditEntry.of("pdf_import.file_deleted", PdfImportService.RESOURCE, pdf.getId())
                            .creditor(pdf.getCreditorId()).meta("fileSha256", pdf.getFileSha256())
                            .reason("retenção de " + properties.retention().toDays() + " dias"));
                    count++;
                }
                return count;
            });
            metrics.filesDeleted(deleted);
            if (deleted > 0) {
                LOG.atInfo().addKeyValue("tenantId", tenantId).addKeyValue("deleted", deleted)
                        .log("Retenção dos PDFs da importação executada");
            }
            return deleted;
        } catch (RuntimeException e) {
            LOG.atError().addKeyValue("tenantId", tenantId).setCause(e).log("Falha na retenção dos PDFs da importação");
            return 0;
        }
    }
}
