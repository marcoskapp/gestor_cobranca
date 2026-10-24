package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.IngestionProperties;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.FileContent;
import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatch;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatchStatus;
import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.domain.model.ImportFormat;
import br.com.gestorcobranca.ingestion.domain.model.ImportLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportMode;
import br.com.gestorcobranca.ingestion.domain.model.ImportSource;
import br.com.gestorcobranca.ingestion.domain.repository.ImportBatchRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportLayoutRepository;
import br.com.gestorcobranca.ingestion.domain.service.FileSignature;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Criação de lotes, compartilhada pelo envio direto, pela API, pelo reprocessamento e pela conferência: escopo da
 * carteira, layout, verificação do tipo pelo conteúdo, limite de tamanho, armazenamento do original (removido se a
 * transação for desfeita), recusa de arquivo duplicado e enfileiramento do processamento. Participa da transação do
 * chamador.
 */
@Component
class BatchCreation {

    static final String RESOURCE = "import_batch";
    private static final Logger LOG = LoggerFactory.getLogger(BatchCreation.class);
    private static final int FILE_NAME_MAX = 200;
    private static final List<ImportBatchStatus> IN_FLIGHT = List.of(ImportBatchStatus.RECEIVED, ImportBatchStatus.PROCESSING);
    private static final List<ImportBatchStatus> IMPORTED = List.of(ImportBatchStatus.COMPLETED,
            ImportBatchStatus.COMPLETED_WITH_ERRORS);

    private final ImportBatchRepository batches;
    private final ImportLayoutRepository layouts;
    private final PortfolioUseCases portfolios;
    private final FileStorage fileStorage;
    private final JobQueue jobQueue;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final IngestionMetrics metrics;
    private final IngestionProperties properties;

    BatchCreation(ImportBatchRepository batches, ImportLayoutRepository layouts, PortfolioUseCases portfolios,
                  FileStorage fileStorage, JobQueue jobQueue, AccessScope accessScope, AuditTrail auditTrail,
                  IngestionMetrics metrics, IngestionProperties properties) {
        this.batches = batches;
        this.layouts = layouts;
        this.portfolios = portfolios;
        this.fileStorage = fileStorage;
        this.jobQueue = jobQueue;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.properties = properties;
    }

    PortfolioView requirePortfolio(UUID portfolioId) {
        Guard.require(portfolioId != null, "import_batch.portfolio_required", "Carteira obrigatória");
        PortfolioView portfolio = portfolios.requireAcceptingImports(portfolioId);
        accessScope.requireCreditor(portfolio.creditorId());
        return portfolio;
    }

    FileLayout resolveLayout(UUID layoutId, PortfolioView portfolio, ImportFormat format) {
        if (layoutId == null) {
            return FileLayout.standard(format);
        }
        ImportLayout layout = layouts.findById(layoutId)
                .orElseThrow(() -> ResourceNotFoundException.of("import_layout", layoutId));
        accessScope.requireVisible("import_layout", layoutId, layout.getCreditorId());
        Guard.require(layout.isActive(), "import.layout_inactive", "Layout inativo: use a versão vigente");
        Guard.require(layout.appliesTo(portfolio.creditorId(), portfolio.id()), "import.layout_not_applicable",
                "Layout não pertence ao credor ou à carteira do lote");
        if (layout.getFormat() != format) {
            throw rejected(new BusinessRuleException("import.format_mismatch",
                    "Extensão do arquivo não corresponde ao formato do layout (%s)".formatted(layout.getFormat())));
        }
        return layout.toFileLayout();
    }

    /** Layout do lote original (reprocessamento e conferência a partir de lote existente). */
    FileLayout layoutOf(ImportBatch original) {
        return original.getLayoutId() == null ? FileLayout.standard(original.getSource().format())
                : layouts.findById(original.getLayoutId()).orElseThrow(
                        () -> ResourceNotFoundException.of("import_layout", original.getLayoutId())).toFileLayout();
    }

    String fileName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty()) {
            throw rejected(new BusinessRuleException("import.file_name_required", "Nome do arquivo obrigatório"));
        }
        return name.length() <= FILE_NAME_MAX ? name : name.substring(name.length() - FILE_NAME_MAX);
    }

    ImportFormat formatOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv") || lower.endsWith(".txt")) {
            return ImportFormat.CSV;
        }
        if (lower.endsWith(".xlsx")) {
            return ImportFormat.XLSX;
        }
        throw rejected(new BusinessRuleException("import.file_type_invalid", "Envie um arquivo .csv ou .xlsx"));
    }

    /** Confere tamanho e tipo pelo conteúdo, grava o original e confirma que o conteúdo não mudou durante o envio. */
    StoredFile storeUpload(FileContent content, String fileName, String contentType, long declaredSize,
                           ImportFormat format) {
        long maxSize = properties.maxFileSize().toBytes();
        if (declaredSize > maxSize) {
            throw tooLarge();
        }
        Digest digest = digest(content, maxSize, format);
        StoredFile stored = store(content, fileName, contentType, digest.size());
        Guard.require(stored.sha256().equals(digest.sha256()), "import.file_changed",
                "Conteúdo do arquivo mudou durante o envio");
        return stored;
    }

    StoredFile store(FileContent content, String name, String contentType, long size) {
        StoredFile stored;
        try (InputStream in = content.open()) {
            stored = fileStorage.store(FileCategory.IMPORT, name, contentType, in, size);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o arquivo enviado", e);
        }
        deleteOnRollback(stored.key());
        return stored;
    }

    /** O arquivo já armazenado de um lote existente (sem nova cópia). */
    static StoredFile storedOf(ImportBatch batch) {
        return new StoredFile(null, batch.getFileKey(), batch.getFileName(), batch.getFileContentType(),
                batch.getFileSize(), batch.getFileSha256());
    }

    /**
     * Mesmo conteúdo já importado com sucesso na carteira: recusa, salvo confirmação com motivo.
     * Pré-visualização nunca é bloqueada. Mesmo conteúdo em importação simultânea: sempre recusa.
     */
    Forcing checkDuplicate(UUID portfolioId, String sha256, ImportMode mode, boolean force, String reason) {
        if (mode != ImportMode.IMPORT) {
            return null;
        }
        List<ImportBatch> inFlight = batches.findByPortfolioIdAndFileSha256AndModeAndStatusInOrderByCreatedAtDesc(
                portfolioId, sha256, ImportMode.IMPORT, IN_FLIGHT);
        if (!inFlight.isEmpty()) {
            throw rejected(new ConflictException("import.already_in_progress",
                    "O mesmo arquivo já está em importação nesta carteira",
                    Map.of("batchId", inFlight.getFirst().getId())));
        }
        UUID imported = importedBefore(portfolioId, sha256);
        if (imported == null) {
            return null;
        }
        if (!force) {
            throw rejected(new ConflictException("import.duplicate_file",
                    "Este arquivo já foi importado nesta carteira; confirme o reprocessamento informando o motivo",
                    Map.of("batchId", imported)));
        }
        if (reason == null || reason.isBlank()) {
            throw rejected(new BusinessRuleException("import.force_reason_required",
                    "Informe o motivo do reprocessamento de um arquivo já importado"));
        }
        return new Forcing(imported, reason.strip());
    }

    /** Lote que já importou o mesmo conteúdo com sucesso na carteira, ou nulo. */
    UUID importedBefore(UUID portfolioId, String sha256) {
        List<ImportBatch> imported = batches.findByPortfolioIdAndFileSha256AndModeAndStatusInOrderByCreatedAtDesc(
                portfolioId, sha256, ImportMode.IMPORT, IMPORTED);
        return imported.isEmpty() ? null : imported.getFirst().getId();
    }

    record Forcing(UUID duplicateOf, String reason) {
    }

    ImportBatch create(PortfolioView portfolio, FileLayout layout, ImportSource source, ImportMode mode,
                       StoredFile stored, Forcing forcing, UUID reprocessOf, ImportBatch.Options options) {
        ImportBatch batch = new ImportBatch(portfolio.creditorId(), portfolio.id(), layout, source, mode,
                new ImportBatch.StoredContent(stored.key(), stored.originalName(), stored.contentType(), stored.size(),
                        stored.sha256()),
                forcing == null ? null : new ImportBatch.Forcing(forcing.duplicateOf(), forcing.reason()), reprocessOf,
                options);
        batches.save(batch);
        jobQueue.enqueue(ImportBatchJobHandler.TYPE, new ImportBatchJobHandler.Payload(batch.getId(), 0), null,
                ImportBatchJobHandler.deduplicationKey(batch.getId(), 0));
        AuditEntry audit = AuditEntry.of("import_batch.received", RESOURCE, batch.getId())
                .creditor(batch.getCreditorId())
                .created("portfolioId", batch.getPortfolioId())
                .created("source", batch.getSource())
                .created("mode", batch.getMode())
                .created("layoutCode", batch.getLayoutCode())
                .created("layoutVersion", batch.getLayoutVersion())
                .created("fileSize", batch.getFileSize())
                .created("fileSha256", batch.getFileSha256())
                .meta("reprocessOfBatchId", reprocessOf)
                .meta("reviewId", batch.getReviewId());
        if (batch.getNumberFormat() != null) {
            audit.created("numberFormat", batch.getNumberFormat());
        }
        if (forcing != null) {
            audit.reason(forcing.reason()).meta("duplicateOfBatchId", forcing.duplicateOf()).meta("forced", true);
        }
        auditTrail.record(audit);
        metrics.received(batch);
        LOG.atInfo().addKeyValue("batchId", batch.getId()).addKeyValue("source", source.name())
                .addKeyValue("mode", mode.name()).addKeyValue("fileSize", batch.getFileSize())
                .addKeyValue("reviewId", batch.getReviewId())
                .log("Lote de importação recebido");
        return batch;
    }

    <E extends RuntimeException> E rejected(E e) {
        metrics.rejected(e instanceof DomainException d ? d.code() : "unknown");
        return e;
    }

    private BusinessRuleException tooLarge() {
        return rejected(new BusinessRuleException("import.file_too_large",
                "Arquivo excede o limite de %d MB".formatted(properties.maxFileSize().toMegabytes())));
    }

    /** Hash e tamanho numa passagem, com limite de tamanho e verificação do tipo pelo conteúdo. */
    private Digest digest(FileContent content, long maxSize, ImportFormat format) {
        MessageDigest sha = sha256Digest();
        long size;
        try (InputStream in = new DigestInputStream(content.open(), sha)) {
            byte[] head = in.readNBytes(FileSignature.HEAD_SIZE);
            try {
                FileSignature.check(format, head);
            } catch (ImportFileException e) {
                throw rejected(e);
            }
            size = head.length;
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                size += read;
                if (size > maxSize) {
                    throw tooLarge();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o arquivo enviado", e);
        }
        return new Digest(HexFormat.of().formatHex(sha.digest()), size);
    }

    private record Digest(String sha256, long size) {
    }

    /** O armazenamento não participa da transação: arquivo de lote não criado não fica órfão. */
    private void deleteOnRollback(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        fileStorage.delete(key);
                    } catch (RuntimeException e) {
                        LOG.atError().addKeyValue("storageKey", key).setCause(e)
                                .log("Arquivo de importação de transação desfeita não pôde ser removido");
                    }
                }
            }
        });
    }

    static String sha256(byte[] content) {
        return HexFormat.of().formatHex(sha256Digest().digest(content));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
