package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.PdfImportProperties;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImport;
import br.com.gestorcobranca.ingestion.domain.repository.PdfImportRepository;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Retenção dos PDFs em rodadas: mais candidatos do que o lote são todos descartados numa execução. */
class PdfRetentionTest {

    private final TenantExecution tenantExecution = mock(TenantExecution.class);
    private final PdfImportRepository imports = mock(PdfImportRepository.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final AuditTrail auditTrail = mock(AuditTrail.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final UUID tenantId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private PdfRetention retention(int batch) {
        when(tenantExecution.callInTenant(eq(tenantId), anyString(), any()))
                .thenAnswer(i -> ((Supplier<Object>) i.getArgument(2)).get());
        PdfImportProperties d = PdfImportProperties.defaults();
        PdfImportProperties properties = new PdfImportProperties(d.maxFileSize(), d.maxPages(), d.maxVisualPages(),
                d.visualPagesPerCall(), d.visualDpi(), d.screenDpi(), d.minConfidence(), d.promptMaxLines(),
                d.mapTimeout(), d.mapMaxOutputTokens(), d.visualMaxOutputTokens(), d.repairAttempts(), d.retention(),
                batch);
        return new PdfRetention(mock(TenantDirectory.class), tenantExecution, imports, storage, auditTrail,
                new PdfImportMetrics(registry), properties, Clock.fixed(Instant.parse("2026-11-05T06:00:00Z"),
                ZoneOffset.UTC));
    }

    private static List<PdfImport> pdfs(int count) {
        return IntStream.range(0, count).mapToObj(i -> {
            PdfImport pdf = mock(PdfImport.class);
            when(pdf.getId()).thenReturn(UUID.randomUUID());
            when(pdf.getFileKey()).thenReturn("tenant/imports/pdf-" + UUID.randomUUID() + ".pdf");
            return pdf;
        }).toList();
    }

    @Test
    void moreCandidatesThanTheBatchAreAllDeletedInOneRun() {
        PdfRetention retention = retention(2);
        List<PdfImport> first = pdfs(2);
        List<PdfImport> second = pdfs(2);
        List<PdfImport> last = pdfs(1);
        when(imports.findRetentionCandidates(any(), any(), any())).thenReturn(first, second, last);

        assertThat(retention.runFor(tenantId)).isEqualTo(5);

        verify(tenantExecution, times(3)).callInTenant(eq(tenantId), eq("ingestion.pdf-retention"), any());
        verify(storage, times(5)).delete(anyString());
        verify(auditTrail, times(5)).record(any());
        assertThat(registry.get("ingestion.pdf.retention.deleted").counter().count()).isEqualTo(5);
    }

    @Test
    void stopsAtTheRoundCeiling() {
        PdfRetention retention = retention(1);
        List<PdfImport> one = pdfs(1);
        when(imports.findRetentionCandidates(any(), any(), any())).thenReturn(one);

        assertThat(retention.runFor(tenantId)).isEqualTo(PdfRetention.MAX_ROUNDS);
    }
}
