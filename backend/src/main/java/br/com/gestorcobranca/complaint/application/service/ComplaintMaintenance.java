package br.com.gestorcobranca.complaint.application.service;

import br.com.gestorcobranca.complaint.domain.model.Complaint;
import br.com.gestorcobranca.complaint.domain.repository.ComplaintRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Tarefas periódicas no tenant do contexto: aviso único de prazo vencido. */
@Component
class ComplaintMaintenance {

    static final int BATCH = 200;
    private static final Logger LOG = LoggerFactory.getLogger(ComplaintMaintenance.class);

    private final ComplaintRepository complaints;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ComplaintMetrics metrics;
    private final Clock clock;

    ComplaintMaintenance(ComplaintRepository complaints, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                         ComplaintMetrics metrics, Clock clock) {
        this.complaints = complaints;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Marca as reclamações que passaram do prazo sem resposta: evento {@code complaint.overdue}, métrica e log técnico
     * WARN (para alarme), uma vez por prazo.
     *
     * @return quantidade marcada neste lote
     */
    @Transactional(propagation = Propagation.MANDATORY)
    int flagOverdue() {
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        List<Complaint> due = complaints.findOverdueUnnotified(today, BATCH);
        int flagged = 0;
        for (Complaint c : due) {
            if (c.markOverdueNotified(today, now)) {
                complaints.save(c);
                auditTrail.record(AuditEntry.of("complaint.overdue", "complaint", c.getId())
                        .creditor(c.getCreditorId())
                        .meta("dueDate", c.getDueDate()));
                businessEvents.log(ComplaintEvents.base(BusinessEventTypes.COMPLAINT_OVERDUE, c)
                        .attr("daysLate", ChronoUnit.DAYS.between(c.getDueDate(), today))
                        .attr("assigned", c.getAssigneeId() != null));
                metrics.overdue(c.getChannel().name());
                LOG.atWarn().addKeyValue("complaintId", c.getId()).addKeyValue("channel", c.getChannel().name())
                        .addKeyValue("dueDate", c.getDueDate().toString()).log("Reclamação com prazo de resposta vencido");
                flagged++;
            }
        }
        return flagged;
    }
}
