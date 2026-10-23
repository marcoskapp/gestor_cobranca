package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ImportSummary;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.ingestion.application.IngestionProperties;
import br.com.gestorcobranca.ingestion.application.port.out.ImportRecordReader;
import br.com.gestorcobranca.ingestion.domain.model.BlockTally;
import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatch;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatchRow;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatchStatus;
import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.domain.model.ImportRowIssue;
import br.com.gestorcobranca.ingestion.domain.model.IssueSeverity;
import br.com.gestorcobranca.ingestion.domain.model.ParsedRow;
import br.com.gestorcobranca.ingestion.domain.model.RowIssue;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;
import br.com.gestorcobranca.ingestion.domain.repository.ImportBatchRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportBatchRowRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportLayoutRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportRowIssueRepository;
import br.com.gestorcobranca.ingestion.domain.service.IgnoredColumns;
import br.com.gestorcobranca.ingestion.domain.service.RowValidation;
import br.com.gestorcobranca.ingestion.domain.service.RowValidator;
import br.com.gestorcobranca.ingestion.domain.service.TitleSituationPolicy;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.jobs.PermanentJobFailure;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.storage.FileStorage;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Processamento de um lote em <b>fatias</b> de tempo limitado ({@code app.ingestion.slice-duration}),
 * cada uma executada por um job da fila ({@code JobQueue}, {@code FOR UPDATE SKIP LOCKED}); ao fim
 * da fatia, se ainda houver linhas, o job seguinte continua do checkpoint. Isso mantém cada
 * execução bem abaixo do lease da fila, distribui lotes grandes entre instâncias e permite
 * cancelamento entre blocos.
 * <ol>
 *   <li><b>Leitura prévia</b> (primeira fatia): o arquivo inteiro é lido sem gravar nada —
 *       estrutura, cabeçalho, limite de linhas e colunas ignoradas. Problema de arquivo falha o
 *       lote antes de qualquer gravação.</li>
 *   <li><b>Blocos</b> de {@code block-size} linhas: validação, detecção de títulos repetidos no
 *       arquivo, gravação linha a linha ({@link RowWriter}, transação por linha) e confirmação do
 *       bloco (ocorrências, contadores e checkpoint, numa transação).</li>
 *   <li><b>Encerramento</b>: status final, contadores de devedores, {@code PortfolioLoaded}
 *       (via {@code DebtIngestionPort.completeImport}), evento de negócio e auditoria.</li>
 * </ol>
 * Retomada após queda: linhas até o checkpoint não são relidas; linhas do bloco interrompido que
 * já tinham sido gravadas são reconhecidas pelo registro da linha e contadas sem nova gravação.
 * Todo acesso ao banco usa transações próprias e curtas — nada fica preso à transação do job.
 */
@Component
class ImportBatchProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(ImportBatchProcessor.class);

    private final ImportBatchRepository batches;
    private final ImportBatchRowRepository rows;
    private final ImportRowIssueRepository issues;
    private final ImportLayoutRepository layouts;
    private final ImportRecordReader reader;
    private final FileStorage fileStorage;
    private final RowWriter rowWriter;
    private final DebtIngestionPort debt;
    private final DebtTitleUseCases debtTitles;
    private final PortfolioUseCases portfolios;
    private final BusinessEventLogger businessEvents;
    private final AuditTrail auditTrail;
    private final IngestionMetrics metrics;
    private final IngestionProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    ImportBatchProcessor(ImportBatchRepository batches, ImportBatchRowRepository rows, ImportRowIssueRepository issues,
                         ImportLayoutRepository layouts, ImportRecordReader reader, FileStorage fileStorage,
                         RowWriter rowWriter, DebtIngestionPort debt, DebtTitleUseCases debtTitles,
                         PortfolioUseCases portfolios, BusinessEventLogger businessEvents, AuditTrail auditTrail,
                         IngestionMetrics metrics, IngestionProperties properties, Clock clock,
                         PlatformTransactionManager transactionManager) {
        this.batches = batches;
        this.rows = rows;
        this.issues = issues;
        this.layouts = layouts;
        this.reader = reader;
        this.fileStorage = fileStorage;
        this.rowWriter = rowWriter;
        this.debt = debt;
        this.debtTitles = debtTitles;
        this.portfolios = portfolios;
        this.businessEvents = businessEvents;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
    }

    enum SliceOutcome {
        /** Há linhas a processar: enfileirar a próxima fatia. */
        CONTINUE,
        /** Lote encerrado (concluído, falho ou cancelado). */
        FINISHED
    }

    /** Executa uma fatia do lote. Chamado pelo job, no tenant do lote, como ator de sistema. */
    SliceOutcome runSlice(UUID batchId) {
        Timer.Sample sample = metrics.start();
        long deadline = System.nanoTime() + properties.sliceDuration().toNanos();
        ImportBatch batch = load(batchId);
        String mode = batch.getMode().name();
        if (batch.getStatus().isTerminal()) {
            return SliceOutcome.FINISHED;
        }
        try {
            FileLayout layout = layoutOf(batch);
            if (batch.isAwaitingStart()) {
                start(batch, layout);
            } else {
                // retomada: linhas gravadas antes da queda já registraram suas ocorrências
                tx.executeWithoutResult(s -> {
                    ImportBatch fresh = require(batchId);
                    fresh.reconcileIssuesRecorded((int) issues.countByBatchId(batchId));
                    batches.save(fresh);
                });
            }
            if (batch.writesToDebt()) {
                readTx.executeWithoutResult(s -> portfolios.requireAcceptingImports(batch.getPortfolioId()));
            }
            SliceRun run = new SliceRun(load(batchId), deadline);
            try (InputStream content = fileStorage.open(batch.getFileKey())) {
                reader.read(content, layout, run);
                run.flush();
            } catch (StopSlice stop) {
                metrics.slice(sample, mode, stop.outcome == SliceOutcome.CONTINUE ? "continued" : "cancelled");
                return stop.outcome;
            } catch (IOException e) {
                throw new UncheckedIOException("Falha de leitura do arquivo de importação", e);
            }
            finish(batchId, false);
            metrics.slice(sample, mode, "finished");
            return SliceOutcome.FINISHED;
        } catch (BusinessRuleException e) {
            // arquivo com problema estrutural ou carteira que deixou de aceitar cargas: permanente
            fail(batchId, e.code(), e.getMessage());
            metrics.slice(sample, mode, "failed");
            return SliceOutcome.FINISHED;
        } catch (RuntimeException e) {
            metrics.slice(sample, mode, "error");
            throw e;
        }
    }

    /** Encerra o lote como falho (erro permanente ou falha técnica persistente), em transação própria. */
    void fail(UUID batchId, String code, String reason) {
        ImportBatch batch = tx.execute(s -> {
            ImportBatch b = require(batchId);
            if (b.getStatus().isTerminal()) {
                return null;
            }
            String previous = b.getStatus().name();
            int[] debtors = debtorCounts(b);
            if (b.getIssuesRecorded() < properties.maxIssuesPerBatch()) {
                issues.saveAll(List.of(new ImportRowIssue(batchId, RowIssue.of(0, IssueSeverity.ERROR, null, code, reason))));
                b.recordFileError();
            }
            b.fail(Instant.now(clock), reason, debtors[0], debtors[1]);
            batches.save(b);
            publishLoadedIfWritten(b);
            businessEvents.log(finishedEvent(b, BusinessEventTypes.IMPORT_FAILED).attr("failureCode", code));
            auditTrail.record(AuditEntry.of("import_batch.failed", "import_batch", b.getId())
                    .creditor(b.getCreditorId())
                    .change("status", previous, b.getStatus().name())
                    .reason(reason)
                    .meta("failureCode", code)
                    .meta("rowsRead", b.getRowsRead()));
            return b;
        });
        if (batch != null) {
            metrics.finished(batch);
            LOG.atWarn().addKeyValue("batchId", batchId).addKeyValue("code", code)
                    .log("Lote de importação encerrado com falha");
        }
    }

    // ---------------------------------------------------------------- leitura prévia

    private void start(ImportBatch batch, FileLayout layout) {
        PreScan scan = new PreScan(properties.maxRows());
        try (InputStream content = fileStorage.open(batch.getFileKey())) {
            reader.read(content, layout, scan);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha de leitura do arquivo de importação", e);
        }
        List<RowIssue> fileWarnings = IgnoredColumns.issues(scan.ignored);
        ImportBatch started = tx.execute(s -> {
            ImportBatch b = require(batch.getId());
            b.begin(Instant.now(clock), scan.rows);
            if (!fileWarnings.isEmpty()) {
                issues.saveAll(fileWarnings.stream().map(i -> new ImportRowIssue(b.getId(), i)).toList());
                BlockTally tally = new BlockTally();
                tally.fileWarnings(fileWarnings.size());
                b.recordBlock(tally, 0, 0, fileWarnings.size());
            }
            batches.save(b);
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.IMPORT_STARTED)
                    .creditor(b.getCreditorId()).portfolio(b.getPortfolioId())
                    .reference("import_batch", b.getId())
                    .attr("source", b.getSource().name())
                    .attr("mode", b.getMode().name())
                    .attr("layoutCode", b.getLayoutCode())
                    .attr("layoutVersion", b.getLayoutVersion())
                    .attr("totalRows", scan.rows)
                    .attr("fileSize", b.getFileSize())
                    .attr("forced", b.isForced())
                    .attr("reprocess", b.getReprocessOfBatchId() != null)
                    .attr("ignoredColumns", scan.ignored.size())
                    .attr("requestedBy", b.getCreatedBy()));
            return b;
        });
        LOG.atInfo().addKeyValue("batchId", started.getId()).addKeyValue("totalRows", scan.rows)
                .addKeyValue("mode", started.getMode().name()).log("Processamento de lote de importação iniciado");
    }

    /** Conta as linhas e captura as colunas ignoradas, sem validar nem gravar. */
    private static final class PreScan implements ImportRecordReader.RecordSink {

        private final int maxRows;
        private long rows;
        private List<String> ignored = List.of();

        PreScan(int maxRows) {
            this.maxRows = maxRows;
        }

        @Override
        public void row(SourceRow row) {
            if (++rows > maxRows) {
                throw new ImportFileException("import.too_many_rows",
                        "Arquivo excede o limite de %d linhas por lote; divida o arquivo".formatted(maxRows));
            }
        }

        @Override
        public void ignoredColumns(List<String> columns) {
            ignored = List.copyOf(columns);
        }
    }

    // ---------------------------------------------------------------- blocos

    /** Acumula linhas após o checkpoint em blocos e os processa. */
    private final class SliceRun implements ImportRecordReader.RecordSink {

        private final UUID batchId;
        private final boolean writes;
        private final String mode;
        private final RowWriter.Target target;
        private final RowValidator validator;
        private final long deadline;
        private final List<SourceRow> block = new ArrayList<>();
        private long checkpoint;
        private int issuesRecorded;

        SliceRun(ImportBatch batch, long deadline) {
            this.batchId = batch.getId();
            this.writes = batch.writesToDebt();
            this.mode = batch.getMode().name();
            this.target = new RowWriter.Target(batch.getId(), batch.getCreditorId(), batch.getPortfolioId(),
                    batch.getId().toString());
            this.validator = new RowValidator(LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)));
            this.deadline = deadline;
            this.checkpoint = batch.getLastProcessedRow();
            this.issuesRecorded = batch.getIssuesRecorded();
        }

        @Override
        public void row(SourceRow row) {
            if (row.rowNumber() <= checkpoint) {
                return;
            }
            block.add(row);
            if (block.size() >= properties.blockSize()) {
                processBlock(false);
            }
        }

        /** Fim do arquivo: o último bloco não encerra a fatia por tempo — o lote é concluído em seguida. */
        void flush() {
            if (!block.isEmpty()) {
                processBlock(true);
            }
        }

        private void processBlock(boolean last) {
            List<SourceRow> current = List.copyOf(block);
            block.clear();
            List<String> keys = current.stream().map(RowValidator::titleKey).filter(Objects::nonNull).distinct().toList();
            Map<String, ImportBatchRow> known = keys.isEmpty() ? Map.of() : readTx.execute(s ->
                    rows.findByBatchIdAndExternalIdIn(batchId, keys).stream()
                            .collect(Collectors.toMap(ImportBatchRow::getExternalId, Function.identity())));
            Map<String, Long> firstInBlock = new HashMap<>();
            BlockTally tally = new BlockTally();
            List<ImportBatchRow> toRegister = new ArrayList<>();
            List<RowIssue> blockIssues = new ArrayList<>();
            int rowIssuesStored = 0;

            for (SourceRow source : current) {
                String key = RowValidator.titleKey(source);
                ImportBatchRow prior = key == null ? null : known.get(key);
                Long duplicateOf = null;
                if (prior != null && prior.getRowNumber() == source.rowNumber()) {
                    resumed(tally, prior, validator.validate(source, null));
                    continue;
                }
                if (prior != null) {
                    duplicateOf = prior.getRowNumber();
                } else if (key != null) {
                    duplicateOf = firstInBlock.putIfAbsent(key, source.rowNumber());
                }
                RowValidation validation = validator.validate(source, duplicateOf);
                boolean registrable = key != null && duplicateOf == null;
                if (validation.valid().isEmpty()) {
                    tally.rejectedRow(validation.errors().size(), validation.warnings().size());
                    blockIssues.addAll(validation.issues());
                    if (registrable) {
                        toRegister.add(ImportBatchRow.registered(batchId, key, source.rowNumber()));
                    }
                    continue;
                }
                ParsedRow parsed = validation.valid().get();
                if (!writes) {
                    List<RowIssue> warnings = new ArrayList<>(validation.warnings());
                    previewSituation(parsed).ifPresent(warnings::add);
                    tally.validRow(parsed.principalAmount(), warnings.size(), null, false);
                    blockIssues.addAll(warnings);
                    toRegister.add(ImportBatchRow.registered(batchId, key, source.rowNumber()));
                    continue;
                }
                int budget = properties.maxIssuesPerBatch() - issuesRecorded - rowIssuesStored - blockIssues.size();
                RowWriter.Result result = rowWriter.write(target, parsed, validation.warnings(), budget);
                if (result.rejected()) {
                    tally.rejectedRow(result.errors().size(), validation.warnings().size());
                    blockIssues.addAll(validation.warnings());
                    blockIssues.addAll(result.errors());
                    toRegister.add(ImportBatchRow.registered(batchId, key, source.rowNumber()));
                } else {
                    ImportBatchRow written = result.row();
                    tally.validRow(parsed.principalAmount(), written.getWarningCount(), written.getTitleOutcome(),
                            written.isTitleSuspended());
                    rowIssuesStored += result.issuesStored();
                }
            }
            commit(current.getLast().rowNumber(), tally, toRegister, blockIssues, rowIssuesStored, last);
        }

        /** Linha já tratada antes de uma queda: contada pelo resultado gravado, sem nova gravação. */
        private void resumed(BlockTally tally, ImportBatchRow prior, RowValidation validation) {
            BigDecimal principal = validation.valid().map(ParsedRow::principalAmount).orElse(BigDecimal.ZERO);
            if (prior.isHandled()) {
                tally.validRow(principal, prior.getWarningCount(), prior.getTitleOutcome(), prior.isTitleSuspended());
            } else if (validation.valid().isPresent()) {
                tally.validRow(principal, validation.warnings().size(), null, false);
            } else {
                tally.rejectedRow(validation.errors().size(), validation.warnings().size());
            }
        }

        private void commit(long lastRow, BlockTally tally, List<ImportBatchRow> toRegister, List<RowIssue> blockIssues,
                            int rowIssuesStored, boolean last) {
            long expected = checkpoint;
            ImportBatch committed = tx.execute(s -> {
                ImportBatch b = require(batchId);
                if (!b.isInProgress()) {
                    throw new StopSlice(SliceOutcome.FINISHED);
                }
                int room = Math.max(0, properties.maxIssuesPerBatch() - issuesRecorded - rowIssuesStored);
                List<ImportRowIssue> stored = blockIssues.stream().limit(room)
                        .map(i -> new ImportRowIssue(batchId, i)).toList();
                rows.saveAll(toRegister);
                issues.saveAll(stored);
                b.recordBlock(tally, expected, lastRow, rowIssuesStored + stored.size());
                return batches.save(b);
            });
            metrics.block(mode, tally, committed.getIssuesRecorded() - issuesRecorded);
            checkpoint = committed.getLastProcessedRow();
            issuesRecorded = committed.getIssuesRecorded();
            if (committed.isCancelRequested()) {
                finish(batchId, true);
                throw new StopSlice(SliceOutcome.FINISHED);
            }
            if (!last && System.nanoTime() > deadline) {
                LOG.atInfo().addKeyValue("batchId", batchId).addKeyValue("checkpoint", checkpoint)
                        .log("Fatia de processamento encerrada pelo tempo; o lote continua em nova execução");
                throw new StopSlice(SliceOutcome.CONTINUE);
            }
        }

        /** Pré-visualização: antecipa o tratamento de títulos informados como pagos/cancelados. */
        private Optional<RowIssue> previewSituation(ParsedRow row) {
            if (!TitleSituationPolicy.requiresLookup(row.situation())) {
                return Optional.empty();
            }
            String status = readTx.execute(s -> rows.latestTitleId(target.creditorId(), row.titleExternalId())
                    .map(id -> debtTitles.get(id).status()).orElse(null));
            TitleSituationPolicy.Decision decision = TitleSituationPolicy.decide(row.situation(), status);
            return decision.warningCode() == null ? Optional.empty()
                    : Optional.of(RowIssue.of(row.rowNumber(), IssueSeverity.WARNING,
                    CanonicalField.TITLE_SITUATION.column(), decision.warningCode(), decision.warningMessage()));
        }
    }

    /** Interrompe a leitura do arquivo ao fim de um bloco (tempo esgotado ou lote encerrado). */
    private static final class StopSlice extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final transient SliceOutcome outcome;

        StopSlice(SliceOutcome outcome) {
            super(null, null, false, false);
            this.outcome = outcome;
        }
    }

    // ---------------------------------------------------------------- encerramento

    private void finish(UUID batchId, boolean cancelled) {
        ImportBatch batch = tx.execute(s -> {
            ImportBatch b = require(batchId);
            if (!b.isInProgress()) {
                return null;
            }
            int[] debtors = debtorCounts(b);
            String eventType;
            if (cancelled) {
                b.stopCancelled(Instant.now(clock), debtors[0], debtors[1]);
                eventType = BusinessEventTypes.IMPORT_CANCELLED;
            } else {
                ImportBatchStatus status = b.complete(Instant.now(clock), debtors[0], debtors[1]);
                eventType = status == ImportBatchStatus.FAILED ? BusinessEventTypes.IMPORT_FAILED
                        : BusinessEventTypes.IMPORT_COMPLETED;
            }
            batches.save(b);
            publishLoadedIfWritten(b);
            BusinessEvent event = finishedEvent(b, eventType);
            if (b.getStatus() == ImportBatchStatus.FAILED) {
                event.attr("failureCode", "import.no_rows");
            }
            businessEvents.log(event);
            auditTrail.record(AuditEntry.of("import_batch.finished", "import_batch", b.getId())
                    .creditor(b.getCreditorId())
                    .change("status", b.getMode().workingStatus().name(), b.getStatus().name())
                    .meta("rowsRead", b.getRowsRead())
                    .meta("rowsWithError", b.getRowsWithError())
                    .meta("titlesCreated", b.getTitlesCreated())
                    .meta("titlesUpdated", b.getTitlesUpdated())
                    .meta("titlesSuspended", b.getTitlesSuspended()));
            return b;
        });
        if (batch != null) {
            metrics.finished(batch);
            LOG.atInfo().addKeyValue("batchId", batchId).addKeyValue("status", batch.getStatus().name())
                    .addKeyValue("rowsRead", batch.getRowsRead())
                    .addKeyValue("durationMs", durationMs(batch))
                    .log("Lote de importação encerrado");
        }
    }

    /**
     * {@code PortfolioLoaded} sempre que o lote criou ou alterou títulos — também em lote falho ou
     * cancelado, pois o que foi gravado já está na carteira e precisa de caso de cobrança.
     */
    private void publishLoadedIfWritten(ImportBatch b) {
        if (b.writesToDebt() && b.getTitlesCreated() + b.getTitlesUpdated() > 0) {
            debt.completeImport(new ImportSummary(b.getId(), b.getCreditorId(), b.getPortfolioId(), b.getTitlesCreated(),
                    b.getTitlesUpdated(), (int) rows.countDebtorsWithChangedTitles(b.getId())));
        }
    }

    private BusinessEvent finishedEvent(ImportBatch b, String type) {
        BusinessEvent event = BusinessEvent.of(type)
                .creditor(b.getCreditorId()).portfolio(b.getPortfolioId())
                .reference("import_batch", b.getId())
                .amount(b.getPrincipalTotal())
                .attr("source", b.getSource().name())
                .attr("mode", b.getMode().name())
                .attr("status", b.getStatus().name())
                .attr("layoutCode", b.getLayoutCode())
                .attr("totalRows", b.getTotalRows())
                .attr("rowsRead", b.getRowsRead())
                .attr("rowsValid", b.getRowsValid())
                .attr("rowsWithError", b.getRowsWithError())
                .attr("errors", b.getErrors())
                .attr("warnings", b.getWarnings())
                .attr("debtorsCreated", b.getDebtorsCreated())
                .attr("debtorsUpdated", b.getDebtorsUpdated())
                .attr("titlesCreated", b.getTitlesCreated())
                .attr("titlesUpdated", b.getTitlesUpdated())
                .attr("titlesUnchanged", b.getTitlesUnchanged())
                .attr("titlesSkipped", b.getTitlesSkipped())
                .attr("titlesSuspended", b.getTitlesSuspended())
                .attr("errorRate", b.getRowsRead() == 0 ? 0 : Math.round(b.getRowsWithError() * 10000.0 / b.getRowsRead()) / 100.0)
                .attr("requestedBy", b.getCreatedBy());
        Long duration = durationMs(b);
        if (duration != null) {
            event.durationMs(duration);
        }
        return event;
    }

    private static Long durationMs(ImportBatch b) {
        return b.getStartedAt() == null || b.getFinishedAt() == null ? null
                : Duration.between(b.getStartedAt(), b.getFinishedAt()).toMillis();
    }

    /** [criados, alterados] — só a importação grava devedores. */
    private int[] debtorCounts(ImportBatch b) {
        if (!b.writesToDebt()) {
            return new int[]{0, 0};
        }
        return new int[]{(int) rows.countCreatedDebtors(b.getId()), (int) rows.countUpdatedDebtors(b.getId())};
    }

    // ---------------------------------------------------------------- apoio

    private FileLayout layoutOf(ImportBatch batch) {
        if (batch.getLayoutId() == null) {
            return FileLayout.standard(batch.getSource().format());
        }
        return read(() -> layouts.findById(batch.getLayoutId())
                .orElseThrow(() -> new PermanentJobFailure("Layout do lote não encontrado: " + batch.getLayoutId()))
                .toFileLayout());
    }

    private ImportBatch load(UUID batchId) {
        return read(() -> batches.findById(batchId)
                .orElseThrow(() -> new PermanentJobFailure("Lote de importação não encontrado: " + batchId)));
    }

    private ImportBatch require(UUID batchId) {
        return batches.findById(batchId).orElseThrow(() -> ResourceNotFoundException.of("import_batch", batchId));
    }

    private <T> T read(Supplier<T> action) {
        return readTx.execute(s -> action.get());
    }
}
