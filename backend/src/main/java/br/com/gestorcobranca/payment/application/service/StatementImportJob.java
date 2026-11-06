package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.out.StatementParser.ParsedLine;
import br.com.gestorcobranca.payment.application.port.out.StatementParser.StatementFormatException;
import br.com.gestorcobranca.payment.domain.model.StatementImport;
import br.com.gestorcobranca.payment.domain.model.StatementImportStatus;
import br.com.gestorcobranca.payment.domain.repository.BankStatementRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Processamento de um extrato importado ({@code payment.statement_import}) sem transação longa (decisão 868):
 * <ol>
 *   <li>transação curta: marca a importação {@code PROCESSING} (ou retoma uma tentativa interrompida);</li>
 *   <li>sem transação: lê o arquivo no armazenamento e interpreta com o leitor do formato;</li>
 *   <li>lotes de {@code app.payment.statement.chunk-size} linhas, cada um na própria transação: concilia linha a
 *       linha ({@link StatementMatcher}) e grava. Os locks de pagamentos e instruções duram só o lote, e as baixas
 *       confirmadas seguem para a negociação a cada lote;</li>
 *   <li>transação curta: contagens apuradas sobre as linhas gravadas e conclusão.</li>
 * </ol>
 * Falha no meio desfaz só o lote corrente; a nova tentativa pula as linhas já gravadas ({@code (import_id,
 * line_number)} único), sem baixa duplicada, e refaz a impressão digital das linhas repetidas na ordem do arquivo.
 * Esgotadas as tentativas, a importação fica {@code FAILED} ({@code statement.processing_failed}) com as linhas já
 * gravadas e a primeira linha do lote interrompido ({@code failureLine}). Arquivo ilegível → {@code FAILED} com o
 * código do erro e a linha apontada pelo leitor (sem nenhuma baixa). A nova tentativa pedida por uma pessoa
 * ({@link ReconciliationService#retryImport}) volta a importação a {@code RECEIVED} e enfileira este job de novo.
 */
@Component
class StatementImportJob implements JobHandler<StatementImportJob.Payload> {

    static final String TYPE = "payment.statement_import";
    static final String PROCESSING_FAILED = "statement.processing_failed";
    private static final Logger LOG = LoggerFactory.getLogger(StatementImportJob.class);

    private final BankStatementRepository statements;
    private final ReconciliationService reconciliation;
    private final StatementMatcher matcher;
    private final FileStorage fileStorage;
    private final AuditTrail auditTrail;
    private final PaymentMetrics metrics;
    private final PaymentProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    StatementImportJob(BankStatementRepository statements, ReconciliationService reconciliation, StatementMatcher matcher,
                       FileStorage fileStorage, AuditTrail auditTrail, PaymentMetrics metrics,
                       PaymentProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.statements = statements;
        this.reconciliation = reconciliation;
        this.matcher = matcher;
        this.fileStorage = fileStorage;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * @param attempt nova tentativa a que o job pertence ({@code retry_count} da importação quando foi enfileirado);
     *                nulo nos jobs anteriores a este campo, tratado como 0
     */
    record Payload(UUID importId, Integer attempt) {

        int attemptOrZero() {
            return attempt == null ? 0 : attempt;
        }
    }

    static void enqueue(JobQueue jobs, UUID importId) {
        jobs.enqueue(TYPE, new Payload(importId, 0), null, "statement:" + importId);
    }

    /**
     * Nova tentativa pedida depois de {@code FAILED}: chave própria por tentativa, para não ser absorvida pelo job
     * anterior que ainda não saiu de {@code RUNNING} (a importação já está {@code FAILED} antes de o job morrer). O
     * job leva o número da tentativa: o de uma tentativa anterior que ainda rodar (retentativa automática atrasada)
     * não mexe na importação da tentativa nova (barreira por {@code retry_count}).
     */
    static void enqueueRetry(JobQueue jobs, UUID importId, int retry) {
        jobs.enqueue(TYPE, new Payload(importId, retry), null, "statement:" + importId + ":retry:" + retry);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public int maxAttempts() {
        return 3;
    }

    /** Abre as próprias transações curtas: leitura do arquivo fora delas e cada lote na sua (decisão 852). */
    @Override
    public boolean transactional() {
        return false;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        UUID importId = payload.importId();
        int attempt = payload.attemptOrZero();
        StatementImport source = tx.execute(s -> statements.findImportForUpdate(importId)
                .filter(i -> i.getRetryCount() == attempt)
                .filter(i -> i.startOrResume(Instant.now(clock)))
                .map(statements::save)
                .orElse(null));
        if (source == null) {
            return;
        }
        // primeira linha do lote em andamento: onde a importação parou se a falha a interromper
        int[] stoppedAt = {0};
        try {
            List<ParsedLine> lines;
            try (InputStream in = fileStorage.open(source.getFileKey())) {
                lines = reconciliation.parser(source.getFormat()).parse(in, properties.statement().maxLines());
            } catch (StatementFormatException e) {
                rejectFile(importId, attempt, e);
                return;
            } catch (IOException e) {
                throw new UncheckedIOException("Falha ao ler o extrato armazenado", e);
            }
            if (processInChunks(importId, attempt, lines, stoppedAt)) {
                finish(importId, attempt);
            }
        } catch (RuntimeException e) {
            // o PROCESSING já foi confirmado: sem nova tentativa, a importação não pode ficar em processamento
            if (context.attempt() >= maxAttempts() || isPermanent(e)) {
                failProcessing(importId, attempt, stoppedAt[0]);
            }
            throw e;
        }
    }

    /** Resultado de um lote: se a importação segue em processamento e quantas linhas já gravadas foram puladas. */
    private record ChunkResult(boolean processing, int resumed) {
    }

    /** @return {@code false} se a importação saiu de processamento no meio (outra execução a concluiu) */
    private boolean processInChunks(UUID importId, int attempt, List<ParsedLine> lines, int[] stoppedAt) {
        // a impressão digital das linhas repetidas depende da ordem no arquivo: calculada para todas, gravadas ou não
        Map<String, Integer> occurrences = new HashMap<>();
        int[] order = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            order[i] = StatementMatcher.occurrence(lines.get(i), occurrences);
        }
        int chunkSize = properties.statement().chunkSize();
        for (int from = 0; from < lines.size(); from += chunkSize) {
            int start = from;
            int end = Math.min(from + chunkSize, lines.size());
            stoppedAt[0] = lines.get(start).lineNumber();
            ChunkResult result = tx.execute(s -> processChunk(importId, attempt, lines, order, start, end));
            if (result == null || !result.processing()) {
                return false;
            }
            // contado só depois do commit do lote: lote desfeito não infla a métrica
            if (result.resumed() > 0) {
                metrics.statementLinesResumed(result.resumed());
            }
        }
        // todos os lotes confirmados: falha na conclusão não aponta linha
        stoppedAt[0] = 0;
        return true;
    }

    private ChunkResult processChunk(UUID importId, int attempt, List<ParsedLine> lines, int[] order, int start,
                                     int end) {
        // o lock da importação serializa execuções concorrentes (lease perdido) e firma a leitura das já gravadas
        StatementImport current = statements.findImportForUpdate(importId).orElse(null);
        if (!owns(current, attempt)) {
            return new ChunkResult(false, 0);
        }
        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        for (int i = start; i < end; i++) {
            first = Math.min(first, lines.get(i).lineNumber());
            last = Math.max(last, lines.get(i).lineNumber());
        }
        Set<Integer> done = statements.lineNumbers(importId, first, last);
        int resumed = 0;
        for (int i = start; i < end; i++) {
            ParsedLine line = lines.get(i);
            if (done.contains(line.lineNumber())) {
                resumed++;
            } else {
                matcher.process(current, line, order[i]);
            }
        }
        return new ChunkResult(true, resumed);
    }

    /** A importação está em processamento e na tentativa deste job (job de tentativa anterior não mexe nela). */
    private static boolean owns(StatementImport source, int attempt) {
        return source != null && source.isProcessing() && source.getRetryCount() == attempt;
    }

    private void finish(UUID importId, int attempt) {
        tx.executeWithoutResult(s -> {
            StatementImport source = statements.findImportForUpdate(importId).orElse(null);
            if (!owns(source, attempt)) {
                return;
            }
            source.finish(statements.countLinesByStatus(importId), Instant.now(clock));
            statements.save(source);
            metrics.statementImport(source.getFormat().name(), StatementImportStatus.PROCESSED.name());
            auditTrail.record(AuditEntry.of("statement_import.processed", ReconciliationService.RESOURCE, source.getId())
                    .creditor(source.getCreditorId()).change("status", StatementImportStatus.PROCESSING,
                            StatementImportStatus.PROCESSED)
                    .meta("totalLines", source.getTotalLines()).meta("reconciledLines", source.getReconciledLines())
                    .meta("createdPayments", source.getCreatedPayments()).meta("divergentLines", source.getDivergentLines())
                    .meta("ignoredLines", source.getIgnoredLines()));
            LOG.atInfo().addKeyValue("importId", source.getId()).addKeyValue("lines", source.getTotalLines())
                    .addKeyValue("reconciled", source.getReconciledLines())
                    .addKeyValue("divergent", source.getDivergentLines()).log("Extrato conciliado");
        });
    }

    private void rejectFile(UUID importId, int attempt, StatementFormatException e) {
        tx.executeWithoutResult(s -> {
            StatementImport source = statements.findImportForUpdate(importId).orElse(null);
            if (!owns(source, attempt)) {
                return;
            }
            source.failProcessing(e.code(), e.lineNumber(), statements.countLinesByStatus(importId), Instant.now(clock));
            statements.save(source);
            metrics.statementImport(source.getFormat().name(), StatementImportStatus.FAILED.name());
            auditTrail.record(AuditEntry.of("statement_import.failed", ReconciliationService.RESOURCE, source.getId())
                    .creditor(source.getCreditorId()).change("status", StatementImportStatus.PROCESSING,
                            StatementImportStatus.FAILED)
                    .meta("failureCode", source.getFailureCode()).meta("failureLine", source.getFailureLine())
                    .meta("totalLines", source.getTotalLines()));
            LOG.atWarn().addKeyValue("importId", source.getId()).addKeyValue("code", e.code())
                    .addKeyValue("line", e.lineNumber()).log("Extrato recusado: arquivo inválido");
        });
    }

    /**
     * Última tentativa falhou no meio: as linhas (e baixas) já gravadas ficam e a importação sai de processamento
     * (decisão 873).
     */
    private void failProcessing(UUID importId, int attempt, int stoppedAt) {
        try {
            tx.executeWithoutResult(s -> {
                StatementImport source = statements.findImportForUpdate(importId).orElse(null);
                if (!owns(source, attempt)) {
                    return;
                }
                source.failProcessing(PROCESSING_FAILED, stoppedAt, statements.countLinesByStatus(importId),
                        Instant.now(clock));
                statements.save(source);
                metrics.statementImport(source.getFormat().name(), StatementImportStatus.FAILED.name());
                auditTrail.record(AuditEntry.of("statement_import.failed", ReconciliationService.RESOURCE, source.getId())
                        .creditor(source.getCreditorId()).change("status", StatementImportStatus.PROCESSING,
                                StatementImportStatus.FAILED)
                        .meta("failureCode", PROCESSING_FAILED).meta("failureLine", source.getFailureLine())
                        .meta("totalLines", source.getTotalLines())
                        .meta("reconciledLines", source.getReconciledLines())
                        .meta("createdPayments", source.getCreatedPayments())
                        .meta("divergentLines", source.getDivergentLines()));
                LOG.atError().addKeyValue("importId", source.getId()).addKeyValue("lines", source.getTotalLines())
                        .log("Extrato interrompido: tentativas esgotadas com parte das linhas gravadas");
            });
        } catch (RuntimeException e) {
            LOG.atError().addKeyValue("importId", importId).setCause(e).log("Falha ao registrar a interrupção do extrato");
        }
    }
}
