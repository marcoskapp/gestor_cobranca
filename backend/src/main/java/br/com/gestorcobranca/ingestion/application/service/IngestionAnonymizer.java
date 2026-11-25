package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.port.out.ImportFileRetentionStore;
import br.com.gestorcobranca.ingestion.application.port.out.ImportFileRetentionStore.ExpiredFile;
import br.com.gestorcobranca.ingestion.application.port.out.ImportFileRetentionStore.PendingErasure;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImport;
import br.com.gestorcobranca.ingestion.domain.repository.PdfImportRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.storage.FileStorage;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * LGPD (SEG-20261124-D01, decisões 1460 e 1461; ADR 0012): devedor anonimizado ({@code DebtorAnonymized}) — o arquivo
 * de carga (CSV, XLSX, registros da API, CSV canônico do PDF) mistura vários devedores, então sai <strong>inteiro</strong>
 * em todos os lotes que o usam, como na retenção {@code IMPORT_FILES}; o PDF de origem das importações PDF com linha do
 * titular também sai. O lote fica (contadores, hash, ocorrências mascaradas e trilha). Arquivo de lote ainda em
 * processamento ou em conferência aberta fica como remoção pendente ({@code ingestion.pending_file_erasure}), concluída
 * pelo job diário {@code ingestion.file-retention} quando o lote encerra. O armazenamento é sempre o último passo da
 * transação: falha no banco desfaz tudo sem deixar chave apontando para objeto apagado.
 */
@Component
class IngestionAnonymizer {

    static final int PENDING_BATCH = 200;
    private static final String REASON = "anonimização do titular (LGPD)";

    private final ImportFileRetentionStore files;
    private final PdfImportRepository pdfImports;
    private final FileStorage storage;
    private final AuditTrail auditTrail;
    private final IngestionMetrics metrics;
    private final Clock clock;

    IngestionAnonymizer(ImportFileRetentionStore files, PdfImportRepository pdfImports, FileStorage storage,
                        AuditTrail auditTrail, IngestionMetrics metrics, Clock clock) {
        this.files = files;
        this.pdfImports = pdfImports;
        this.storage = storage;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.clock = clock;
    }

    void anonymize(UUID debtorId, UUID sourceEventId) {
        Instant now = Instant.now(clock);
        List<String> keys = new ArrayList<>();
        int pending = 0;
        for (UUID pdfId : files.pdfImportsWithDebtor(debtorId)) {
            if (!erasePdf(pdfId, sourceEventId, now, keys) && files.addPendingErasure(null, pdfId, sourceEventId, now)) {
                pending++;
            }
        }
        for (ExpiredFile file : files.filesWithDebtor(debtorId)) {
            if (!eraseFile(file.fileKey(), sourceEventId, now, keys)
                    && files.addPendingErasure(file.fileKey(), null, sourceEventId, now)) {
                pending++;
            }
        }
        keys.forEach(storage::delete);
        metrics.filesDeleted(keys.size(), IngestionMetrics.REASON_ANONYMIZATION);
        metrics.erasuresPending(pending);
    }

    /** @param candidates pendências selecionadas (rodada cheia = pode haver mais) */
    record PendingRound(int candidates, int deleted) {
    }

    /**
     * Conclui uma rodada das remoções pendentes cujos lotes já encerraram (tenant corrente, na transação do chamador);
     * a métrica fica com o job.
     */
    PendingRound completePending() {
        Instant now = Instant.now(clock);
        List<String> keys = new ArrayList<>();
        List<PendingErasure> pending = files.pendingErasures(PENDING_BATCH);
        for (PendingErasure p : pending) {
            boolean done = p.pdfImportId() != null
                    ? erasePdf(p.pdfImportId(), p.sourceEventId(), now, keys)
                    : eraseFile(p.fileKey(), p.sourceEventId(), now, keys);
            if (done) {
                files.removePendingErasure(p.id());
            }
        }
        keys.forEach(storage::delete);
        return new PendingRound(pending.size(), keys.size());
    }

    /** @return falso se a importação PDF ainda está aberta (fica pendente) */
    private boolean erasePdf(UUID pdfId, UUID sourceEventId, Instant now, List<String> keys) {
        PdfImport pdf = pdfImports.findByIdForUpdate(pdfId).orElse(null);
        if (pdf == null || pdf.getFileKey() == null) {
            return true;
        }
        if (pdf.getStatus().open()) {
            return false;
        }
        keys.add(pdf.getFileKey());
        pdf.fileDeleted(now);
        pdfImports.save(pdf);
        auditTrail.record(AuditEntry.of("pdf_import.file_deleted", PdfImportService.RESOURCE, pdf.getId())
                .creditor(pdf.getCreditorId()).meta("fileSha256", pdf.getFileSha256())
                .meta("sourceEventId", sourceEventId).reason(REASON));
        return true;
    }

    /** @return falso se algum lote com o arquivo ainda está aberto (fica pendente) */
    private boolean eraseFile(String fileKey, UUID sourceEventId, Instant now, List<String> keys) {
        List<ExpiredFile> current = files.fileByKey(fileKey);
        if (current.isEmpty()) {
            return true;
        }
        int marked = files.markDeleted(fileKey, now);
        if (marked == 0) {
            return false;
        }
        ExpiredFile file = current.getFirst();
        keys.add(fileKey);
        auditTrail.record(AuditEntry.of("import_batch.file_deleted", BatchCreation.RESOURCE, file.batchId())
                .creditor(file.creditorId()).meta("batches", marked).meta("fileSha256", file.fileSha256())
                .meta("sourceEventId", sourceEventId).reason(REASON));
        return true;
    }
}
