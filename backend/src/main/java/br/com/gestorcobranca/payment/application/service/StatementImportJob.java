package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.out.StatementParser;
import br.com.gestorcobranca.payment.application.port.out.StatementParser.ParsedLine;
import br.com.gestorcobranca.payment.application.port.out.StatementParser.StatementFormatException;
import br.com.gestorcobranca.payment.domain.model.StatementImport;
import br.com.gestorcobranca.payment.domain.model.StatementImportStatus;
import br.com.gestorcobranca.payment.domain.model.StatementLineStatus;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Processamento de um extrato importado ({@code payment.statement_import}), numa transação: lê o
 * arquivo com o leitor do formato, concilia linha a linha ({@link StatementMatcher}) e grava as
 * contagens. Arquivo ilegível → importação {@code FAILED} com o código do erro (sem nenhuma baixa).
 */
@Component
class StatementImportJob implements JobHandler<StatementImportJob.Payload> {

    static final String TYPE = "payment.statement_import";
    private static final Logger LOG = LoggerFactory.getLogger(StatementImportJob.class);

    private final BankStatementRepository statements;
    private final ReconciliationService reconciliation;
    private final StatementMatcher matcher;
    private final FileStorage fileStorage;
    private final AuditTrail auditTrail;
    private final PaymentMetrics metrics;
    private final PaymentProperties properties;
    private final Clock clock;

    StatementImportJob(BankStatementRepository statements, ReconciliationService reconciliation, StatementMatcher matcher,
                       FileStorage fileStorage, AuditTrail auditTrail, PaymentMetrics metrics,
                       PaymentProperties properties, Clock clock) {
        this.statements = statements;
        this.reconciliation = reconciliation;
        this.matcher = matcher;
        this.fileStorage = fileStorage;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    record Payload(UUID importId) {
    }

    static void enqueue(JobQueue jobs, UUID importId) {
        jobs.enqueue(TYPE, new Payload(importId), null, "statement:" + importId);
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

    @Override
    public void handle(JobContext context, Payload payload) {
        StatementImport source = statements.findImportForUpdate(payload.importId()).orElse(null);
        if (source == null || !source.start(Instant.now(clock))) {
            return;
        }
        StatementParser parser = reconciliation.parser(source.getFormat());
        List<ParsedLine> lines;
        try (InputStream in = fileStorage.open(source.getFileKey())) {
            lines = parser.parse(in, properties.statement().maxLines());
        } catch (StatementFormatException e) {
            source.fail(e.code(), Instant.now(clock));
            statements.save(source);
            metrics.statementImport(source.getFormat().name(), StatementImportStatus.FAILED.name());
            LOG.atWarn().addKeyValue("importId", source.getId()).addKeyValue("code", e.code())
                    .addKeyValue("line", e.lineNumber()).log("Extrato recusado: arquivo inválido");
            return;
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o extrato armazenado", e);
        }
        Map<String, Integer> occurrences = new HashMap<>();
        for (ParsedLine line : lines) {
            StatementLineStatus status = matcher.process(source, line, occurrences);
            source.count(status);
        }
        source.finish(Instant.now(clock));
        statements.save(source);
        metrics.statementImport(source.getFormat().name(), StatementImportStatus.PROCESSED.name());
        auditTrail.record(AuditEntry.of("statement_import.processed", ReconciliationService.RESOURCE, source.getId())
                .creditor(source.getCreditorId()).change("status", StatementImportStatus.PROCESSING,
                        StatementImportStatus.PROCESSED)
                .meta("totalLines", source.getTotalLines()).meta("reconciledLines", source.getReconciledLines())
                .meta("createdPayments", source.getCreatedPayments()).meta("divergentLines", source.getDivergentLines())
                .meta("ignoredLines", source.getIgnoredLines()));
        LOG.atInfo().addKeyValue("importId", source.getId()).addKeyValue("lines", source.getTotalLines())
                .addKeyValue("reconciled", source.getReconciledLines()).addKeyValue("divergent", source.getDivergentLines())
                .log("Extrato conciliado");
    }
}
