package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContractRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.UpsertResult;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatchRow;
import br.com.gestorcobranca.ingestion.domain.model.ImportRowIssue;
import br.com.gestorcobranca.ingestion.domain.model.IssueSeverity;
import br.com.gestorcobranca.ingestion.domain.model.ParsedRow;
import br.com.gestorcobranca.ingestion.domain.model.RowIssue;
import br.com.gestorcobranca.ingestion.domain.model.RowOutcome;
import br.com.gestorcobranca.ingestion.domain.repository.ImportBatchRowRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportRowIssueRepository;
import br.com.gestorcobranca.ingestion.domain.service.DebtWarnings;
import br.com.gestorcobranca.ingestion.domain.service.TitleSituationPolicy;
import br.com.gestorcobranca.ingestion.domain.service.TitleSituationPolicy.Decision;
import br.com.gestorcobranca.shared.domain.DomainException;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Grava uma linha válida no módulo debt, em transação própria: devedor, título, tratamento da
 * situação informada pelo credor, registro da linha ({@link ImportBatchRow}) e seus avisos —
 * tudo ou nada. É o que torna a carga retomável sem duplicidade: uma linha cujo registro existe
 * já foi gravada por completo.
 * <p>
 * Violações de regra do debt (exceção de domínio) desfazem a linha e voltam como erro da linha.
 * Conflito de unicidade (outra carga simultânea criando o mesmo devedor) é repetido uma vez — a
 * segunda tentativa encontra o registro e o atualiza. Demais falhas (banco indisponível) sobem
 * para o processamento, que é retomado do checkpoint.
 */
@Component
class RowWriter {

    private static final Logger LOG = LoggerFactory.getLogger(RowWriter.class);

    private final DebtIngestionPort debt;
    private final DebtTitleUseCases debtTitles;
    private final ImportBatchRowRepository rows;
    private final ImportRowIssueRepository issues;
    private final TransactionTemplate requiresNew;
    private final IngestionMetrics metrics;

    RowWriter(DebtIngestionPort debt, DebtTitleUseCases debtTitles, ImportBatchRowRepository rows,
              ImportRowIssueRepository issues, PlatformTransactionManager transactionManager, IngestionMetrics metrics) {
        this.debt = debt;
        this.debtTitles = debtTitles;
        this.rows = rows;
        this.issues = issues;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.metrics = metrics;
    }

    /** Identificação do lote para a gravação. */
    record Target(UUID batchId, UUID creditorId, UUID portfolioId, String batchReference) {
    }

    /**
     * Resultado da linha.
     *
     * @param row          registro da linha (nulo se rejeitada)
     * @param errors       erros (linha rejeitada pelo módulo debt)
     * @param issuesStored ocorrências gravadas na transação da linha
     * @param resumed      a linha já tinha sido gravada (retomada ou execução concorrente)
     */
    record Result(ImportBatchRow row, List<RowIssue> errors, int issuesStored, boolean resumed) {

        boolean rejected() {
            return row == null;
        }
    }

    /**
     * @param warnings    avisos da validação da linha
     * @param issueBudget ocorrências que ainda podem ser gravadas no lote
     */
    Result write(Target target, ParsedRow row, List<RowIssue> warnings, int issueBudget) {
        Timer.Sample sample = metrics.start();
        try {
            Result result = attempt(target, row, warnings, issueBudget);
            metrics.rowWrite(sample, result.rejected() ? "rejected"
                    : result.row().getTitleOutcome() == RowOutcome.SKIPPED ? "skipped" : "written");
            return result;
        } catch (DataIntegrityViolationException first) {
            metrics.rowRetried();
            LOG.atInfo().addKeyValue("batchId", target.batchId()).addKeyValue("row", row.rowNumber())
                    .log("Conflito de unicidade ao gravar linha da carga; nova tentativa");
            Result result = attempt(target, row, warnings, issueBudget);
            metrics.rowWrite(sample, result.rejected() ? "rejected" : "written");
            return result;
        }
    }

    private Result attempt(Target target, ParsedRow row, List<RowIssue> warnings, int issueBudget) {
        try {
            return requiresNew.execute(status -> writeInTransaction(target, row, warnings, issueBudget));
        } catch (DomainException e) {
            return new Result(null, List.of(RowIssue.of(row.rowNumber(),
                    IssueSeverity.ERROR, fieldOf(e.code()), e.code(),
                    e.getMessage())), 0, false);
        }
    }

    private Result writeInTransaction(Target target, ParsedRow row, List<RowIssue> warnings, int issueBudget) {
        List<ImportBatchRow> existing = rows.findByBatchIdAndExternalIdIn(target.batchId(), List.of(row.titleExternalId()));
        if (!existing.isEmpty() && existing.getFirst().getRowNumber() == row.rowNumber()
                && existing.getFirst().isHandled()) {
            return new Result(existing.getFirst(), List.of(), 0, true);
        }
        List<RowIssue> rowIssues = new ArrayList<>(warnings);
        ImportBatchRow record;
        // status do título existente: consultado só quando a decisão depende dele (pago/cancelado)
        UUID titleId = TitleSituationPolicy.requiresLookup(row.situation())
                ? rows.latestTitleId(target.creditorId(), row.titleExternalId()).orElse(null) : null;
        Decision decision = TitleSituationPolicy.decide(row.situation(),
                titleId == null ? null : debtTitles.get(titleId).status());
        if (!decision.write()) {
            if (decision.suspendExisting()) {
                debtTitles.suspend(titleId, TitleSituationPolicy.suspensionReason(row.situation(), target.batchReference()));
            }
            if (decision.warningCode() != null) {
                rowIssues.add(RowIssue.of(row.rowNumber(), IssueSeverity.WARNING,
                        CanonicalField.TITLE_SITUATION.column(), decision.warningCode(), decision.warningMessage()));
            }
            record = ImportBatchRow.skipped(target.batchId(), row.titleExternalId(), row.rowNumber(), titleId,
                    rowIssues.size(), decision.suspendExisting());
        } else {
            UpsertResult debtor = debt.upsertDebtor(debtorRecord(row));
            UpsertResult title = debt.upsertTitle(titleRecord(target, row, debtor.id()), target.batchId());
            debtor.warnings().forEach(code -> rowIssues.add(DebtWarnings.toIssue(row.rowNumber(), code)));
            title.warnings().forEach(code -> rowIssues.add(DebtWarnings.toIssue(row.rowNumber(), code)));
            boolean suspended = false;
            if (row.situation().holdsCollection()
                    && TitleSituationPolicy.suspendAfterWrite(row.situation(), debtTitles.get(title.id()).status())) {
                debtTitles.suspend(title.id(), TitleSituationPolicy.suspensionReason(row.situation(), target.batchReference()));
                suspended = true;
                rowIssues.add(RowIssue.of(row.rowNumber(), IssueSeverity.WARNING,
                        CanonicalField.TITLE_SITUATION.column(), TitleSituationPolicy.suspensionWarning(row.situation()),
                        "Cobrança do título suspensa a pedido do credor"));
            }
            record = ImportBatchRow.written(target.batchId(), row.titleExternalId(), row.rowNumber(), title.id(),
                    outcome(title.outcome()), debtor.id(), outcome(debtor.outcome()), rowIssues.size(), suspended);
        }
        rows.save(record);
        List<ImportRowIssue> stored = rowIssues.stream().limit(Math.max(0, issueBudget))
                .map(i -> new ImportRowIssue(target.batchId(), i)).toList();
        issues.saveAll(stored);
        return new Result(record, List.of(), stored.size(), false);
    }

    private static DebtorRecord debtorRecord(ParsedRow row) {
        List<ContactRecord> contacts = row.contacts().stream()
                .map(c -> new ContactRecord(c.kind().name(), c.value(), null, c.whatsapp()))
                .toList();
        return new DebtorRecord(row.document().number(), row.name(), row.birthDate(), contacts);
    }

    private static TitleRecord titleRecord(Target target, ParsedRow row, UUID debtorId) {
        ContractRecord contract = row.contract() == null ? null : new ContractRecord(row.contract().number(),
                row.contract().product(), row.contract().signedAt(), row.contract().originalAmount());
        return new TitleRecord(target.creditorId(), target.portfolioId(), debtorId, row.titleExternalId(), contract,
                row.installmentNumber(), row.dueDate(), row.principalAmount(), row.reportedBalance(),
                row.balanceReferenceDate());
    }

    private static RowOutcome outcome(DebtIngestionPort.Outcome outcome) {
        return switch (outcome) {
            case CREATED -> RowOutcome.CREATED;
            case UPDATED -> RowOutcome.UPDATED;
            case UNCHANGED -> RowOutcome.UNCHANGED;
        };
    }

    private static String fieldOf(String code) {
        if (code == null) {
            return null;
        }
        if (code.startsWith("debtor.") || code.startsWith("document.")) {
            return CanonicalField.DEBTOR_DOCUMENT.column();
        }
        if (code.startsWith("origin_contract.")) {
            return CanonicalField.CONTRACT_NUMBER.column();
        }
        return code.startsWith("title.") ? CanonicalField.TITLE_EXTERNAL_ID.column() : null;
    }
}
