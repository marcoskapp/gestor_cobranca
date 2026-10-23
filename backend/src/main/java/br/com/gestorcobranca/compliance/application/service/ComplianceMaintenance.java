package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.domain.model.ContactRestriction;
import br.com.gestorcobranca.compliance.domain.model.DataSubjectRequest;
import br.com.gestorcobranca.compliance.domain.repository.ContactRestrictionRepository;
import br.com.gestorcobranca.compliance.domain.repository.DataSubjectRequestRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Tarefas periódicas do módulo executadas no tenant do contexto (transação aberta por
 * {@code TenantExecution}): sinalização de solicitações vencidas e comunicação do fim de
 * vigência de restrições temporárias.
 */
@Component
class ComplianceMaintenance {

    private static final Logger LOG = LoggerFactory.getLogger(ComplianceMaintenance.class);
    private static final int BATCH = 500;

    private final DataSubjectRequestRepository requests;
    private final ContactRestrictionRepository restrictions;
    private final RestrictionRecorder restrictionRecorder;
    private final AuditTrail auditTrail;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    ComplianceMaintenance(DataSubjectRequestRepository requests, ContactRestrictionRepository restrictions,
                          RestrictionRecorder restrictionRecorder, AuditTrail auditTrail, MeterRegistry meterRegistry,
                          Clock clock) {
        this.requests = requests;
        this.restrictions = restrictions;
        this.restrictionRecorder = restrictionRecorder;
        this.auditTrail = auditTrail;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Marca as solicitações abertas que passaram do prazo. Cada uma gera log técnico WARN (para
     * alarme) e incrementa {@code compliance.dsr.overdue}.
     *
     * @return quantidade marcada neste lote
     */
    @Transactional(propagation = Propagation.MANDATORY)
    int flagOverdueRequests() {
        Instant now = Instant.now(clock);
        List<DataSubjectRequest> due = requests.findOpenDueBefore(now, BATCH);
        int flagged = 0;
        for (DataSubjectRequest request : due) {
            if (request.markOverdueIfLate(now)) {
                requests.save(request);
                auditTrail.record(AuditEntry.of("data_subject_request.overdue", "data_subject_request", request.getId())
                        .change("overdue", false, true)
                        .meta("dueAt", request.getDueAt()));
                meterRegistry.counter("compliance.dsr.overdue", "type", request.getType().name()).increment();
                LOG.atWarn().addKeyValue("requestId", request.getId()).addKeyValue("type", request.getType().name())
                        .addKeyValue("status", request.getStatus().name()).addKeyValue("dueAt", request.getDueAt().toString())
                        .log("Solicitação de titular com prazo vencido");
                flagged++;
            }
        }
        return flagged;
    }

    /**
     * Comunica o fim de vigência de restrições temporárias ({@code ContactRestrictionChanged}
     * com {@code restricted = false}) para que a elegibilidade de contato seja recalculada.
     *
     * @return quantidade comunicada neste lote
     */
    @Transactional(propagation = Propagation.MANDATORY)
    int notifyExpiredRestrictions() {
        Instant now = Instant.now(clock);
        List<ContactRestriction> expired = restrictions.findExpiredUnnotified(now, BATCH);
        expired.forEach(r -> restrictionRecorder.expired(r, now));
        meterRegistry.counter("compliance.restrictions.expired").increment(expired.size());
        return expired.size();
    }
}
