package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.service.TitleChangeNotifier.Trigger;
import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.TitleLedgerEntry;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.TitleLedgerEntryRepository;
import br.com.gestorcobranca.debt.domain.service.TitleLedger.Posting;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Persiste o resultado de uma operação no razão: lançamentos novos, cache de saldo/status do
 * título, auditoria e notificação — tudo na transação do chamador, que já detém o lock do título.
 */
@Component
class LedgerRecorder {

    private final DebtTitleRepository titles;
    private final TitleLedgerEntryRepository ledger;
    private final AuditTrail auditTrail;
    private final TitleChangeNotifier notifier;

    LedgerRecorder(DebtTitleRepository titles, TitleLedgerEntryRepository ledger, AuditTrail auditTrail,
                   TitleChangeNotifier notifier) {
        this.titles = titles;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.notifier = notifier;
    }

    List<TitleLedgerEntry> existingEntries(DebtTitle title) {
        return ledger.findByTitleIdOrderByOccurredAtAscIdAsc(title.getId());
    }

    /** Grava a operação; repetição idempotente ({@code !posting.applied()}) não grava nada. */
    void record(DebtTitle title, Posting posting, String auditAction, Trigger trigger) {
        if (!posting.applied()) {
            return;
        }
        ledger.saveAll(posting.entries());
        titles.save(title);
        TitleLedgerEntry first = posting.entries().getFirst();
        AuditEntry audit = AuditEntry.of(auditAction, "debt_title", title.getId())
                .creditor(title.getCreditorId())
                .reason(first.getReason())
                .change("status", posting.previousStatus(), title.getStatus())
                .change("openBalance", posting.previousBalance(), title.getOpenBalance())
                .meta("entryType", first.getEntryType())
                .meta("referenceType", first.getReferenceType())
                .meta("referenceId", first.getReferenceId())
                .meta("total", posting.total())
                .meta("receivedBy", first.getReceivedBy());
        if (posting.previousCreditorPaid() != null
                && !posting.previousCreditorPaid().equals(title.getCreditorPaidTotal())) {
            audit.change("creditorPaidTotal", posting.previousCreditorPaid(), title.getCreditorPaidTotal());
        }
        if (posting.previousSettledByImportBatchId() != null
                && !posting.previousSettledByImportBatchId().equals(title.getSettledByImportBatchId())) {
            // título zerado pela carga do credor que reabriu: o lote deixa de valer (H07 2b, RS-98)
            audit.change("settledByImportBatchId", posting.previousSettledByImportBatchId(),
                    title.getSettledByImportBatchId());
        }
        if (posting.previousSuspensionImportBatchId() != null
                && !posting.previousSuspensionImportBatchId().equals(title.getSuspensionImportBatchId())) {
            // título suspenso pela carga do credor que saiu da suspensão pelo razão: o lote deixa de valer (RC-05)
            audit.change("suspensionImportBatchId", posting.previousSuspensionImportBatchId(),
                    title.getSuspensionImportBatchId());
        }
        posting.entries().forEach(e -> audit.meta("component." + e.getComponent().name(), e.getAmount()));
        auditTrail.record(audit);
        notifier.changed(title, posting.previousStatus(), posting.previousBalance(), trigger);
    }
}
