package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.domain.model.AssignmentMode;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.CollectionTask;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.collection.domain.repository.CollectionTaskRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Tarefas periódicas do módulo no tenant do contexto (transação aberta por
 * {@code TenantExecution}): vencimento de retornos, quebra de promessas e prazo de contestações.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class CollectionMaintenance {

    static final int BATCH = 500;

    private final CollectionTaskRepository tasks;
    private final CollectionCaseRepository cases;
    private final CallbackRecorder callbacks;
    private final CaseRecorder caseRecorder;
    private final CaseAgenda agenda;
    private final PromiseService promises;
    private final DisputeService disputes;
    private final AuditTrail auditTrail;
    private final CaseAccess access;
    private final Clock clock;

    CollectionMaintenance(CollectionTaskRepository tasks, CollectionCaseRepository cases, CallbackRecorder callbacks,
                          CaseRecorder caseRecorder, CaseAgenda agenda, PromiseService promises,
                          DisputeService disputes, AuditTrail auditTrail, CaseAccess access, Clock clock) {
        this.tasks = tasks;
        this.cases = cases;
        this.callbacks = callbacks;
        this.caseRecorder = caseRecorder;
        this.agenda = agenda;
        this.promises = promises;
        this.disputes = disputes;
        this.auditTrail = auditTrail;
        this.access = access;
        this.clock = clock;
    }

    /**
     * Marca como atrasados ({@code MISSED}) os retornos pendentes cuja tolerância passou. O caso
     * continua com o responsável, com a próxima ação no passado: no "próximo caso" do operador ele
     * vem antes de qualquer caso novo da fila.
     *
     * @return quantidade marcada neste lote
     */
    int markMissedCallbacks() {
        Instant now = Instant.now(clock);
        List<CollectionTask> late = tasks.findPendingLateBefore(now, BATCH);
        int marked = 0;
        for (CollectionTask task : late) {
            if (!task.markMissedIfLate(now)) {
                continue;
            }
            tasks.save(task);
            auditTrail.record(AuditEntry.of("task.missed", "task", task.getId())
                    .creditor(task.getCreditorId())
                    .change("status", "PENDING", task.getStatus())
                    .meta("dueAt", task.getDueAt()));
            cases.findDebtorIdById(task.getCaseId()).ifPresent(access::lockDebtor);
            CollectionCase c = cases.findById(task.getCaseId()).orElseThrow();
            callbacks.missed(task, c);
            if (c.getStatus().isActive()) {
                CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
                agenda.refresh(c);
                caseRecorder.commit(c, before, AssignmentMode.ROUTING, null, "callback_missed");
            }
            marked++;
        }
        return marked;
    }

    int breakPromises() {
        return promises.breakOverdue(BATCH);
    }

    int flagOverdueDisputes() {
        return disputes.flagOverdue(BATCH);
    }
}
