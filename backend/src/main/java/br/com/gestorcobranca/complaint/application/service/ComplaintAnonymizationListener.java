package br.com.gestorcobranca.complaint.application.service;

import br.com.gestorcobranca.complaint.domain.model.Complaint;
import br.com.gestorcobranca.complaint.domain.model.ComplaintEvidence;
import br.com.gestorcobranca.complaint.domain.repository.ComplaintEvidenceRepository;
import br.com.gestorcobranca.complaint.domain.repository.ComplaintRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Devedor anonimizado (LGPD): o texto livre das reclamações dele (resumo, resposta) e o protocolo externo saem, e os
 * arquivos das evidências são apagados (registro {@code REMOVED}); ficam canal, assunto, datas e resultado para os
 * indicadores. Consumidor {@code complaint.debtor-anonymized}, idempotente pelo evento.
 */
@Component
class ComplaintAnonymizationListener extends TenantEventListener {

    private final ComplaintRepository complaints;
    private final ComplaintEvidenceRepository evidences;
    private final ComplaintFileCleanup fileCleanup;
    private final AuditTrail auditTrail;
    private final java.time.Clock clock;

    ComplaintAnonymizationListener(ComplaintRepository complaints, ComplaintEvidenceRepository evidences,
                                   ComplaintFileCleanup fileCleanup, AuditTrail auditTrail, java.time.Clock clock) {
        this.complaints = complaints;
        this.evidences = evidences;
        this.fileCleanup = fileCleanup;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(DebtorAnonymized event) {
        handleOnce(event, "complaint.debtor-anonymized", () -> {
            java.time.Instant now = java.time.Instant.now(clock);
            for (Complaint c : complaints.findByDebtorId(event.debtorId())) {
                c.anonymize();
                complaints.save(c);
                int removed = 0;
                for (ComplaintEvidence e : evidences.findByComplaintIdOrderByCreatedAtAsc(c.getId())) {
                    if (e.getStatus() == ComplaintEvidence.Status.REMOVED) {
                        continue;
                    }
                    String key = e.remove(now);
                    evidences.save(e);
                    if (key != null) {
                        fileCleanup.deleteAfterCommit(key);
                    }
                    removed++;
                }
                auditTrail.record(AuditEntry.of("complaint.anonymized", "complaint", c.getId())
                        .creditor(c.getCreditorId()).meta("sourceEventId", event.eventId())
                        .meta("evidencesRemoved", removed));
            }
        });
    }
}
