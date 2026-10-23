package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.domain.model.DeadlineStatus;
import br.com.gestorcobranca.legal.domain.model.LegalDeadline;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.repository.LegalDeadlineRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalMatterRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.legal.LegalDeadlineApproaching;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Varredura dos prazos pendentes (um lote por transação, cursor por id): vencidos sem
 * cumprimento passam a MISSED ({@code legal.deadline_missed}); os demais recebem o alerta do
 * menor nível configurado que já alcançaram ({@code legal.deadline_approaching}), uma vez por
 * nível. Ambos publicam {@link LegalDeadlineApproaching} para notificação.
 */
@Component
class DeadlineMonitor {

    private final LegalDeadlineRepository deadlines;
    private final LegalMatterRepository matters;
    private final ForensicCalendars calendars;
    private final LegalSettingsProvider settings;
    private final MatterRecorder matterRecorder;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final LegalMetrics metrics;
    private final Clock clock;

    DeadlineMonitor(LegalDeadlineRepository deadlines, LegalMatterRepository matters, ForensicCalendars calendars,
                    LegalSettingsProvider settings, MatterRecorder matterRecorder, AuditTrail auditTrail,
                    BusinessEventLogger businessEvents, ApplicationEventPublisher events, LegalMetrics metrics,
                    Clock clock) {
        this.deadlines = deadlines;
        this.matters = matters;
        this.calendars = calendars;
        this.settings = settings;
        this.matterRecorder = matterRecorder;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Processa um lote a partir do cursor. @return último id processado (nulo = fim) */
    @Transactional
    UUID scanBatch(UUID after, int size) {
        List<LegalDeadline> batch = deadlines.findByStatusAndIdGreaterThanOrderByIdAsc(DeadlineStatus.PENDING, after,
                PageRequest.of(0, size));
        LocalDate today = LegalViews.today(clock);
        List<Integer> levels = settings.current().deadlineAlertLevels().stream().sorted().toList();
        for (LegalDeadline d : batch) {
            LegalMatter m = matters.findById(d.getMatterId()).orElseThrow();
            if (d.markMissed(today)) {
                deadlines.save(d);
                auditTrail.record(AuditEntry.of("legal_deadline.missed", DeadlineRecorder.ENTITY, d.getId())
                        .creditor(d.getCreditorId()).change("status", DeadlineStatus.PENDING, DeadlineStatus.MISSED)
                        .meta("dueDate", d.getDueDate()));
                businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_DEADLINE_MISSED, m)
                        .reference(DeadlineRecorder.ENTITY, d.getId()).attr("matterId", m.getId())
                        .attr("kind", d.getKind()).attr("dueDate", d.getDueDate().toString())
                        .attr("responsibleUserId", d.getResponsibleUserId()).attr("lawFirmId", m.getLawFirmId())
                        .attr("stage", m.getStage()));
                publish(d, m, -1, true);
                metrics.deadlineMissed(d.getKind());
                continue;
            }
            int remaining = calendars.remaining(today, d.getDueDate(), d.getCourtCode());
            Integer level = levels.stream().filter(l -> remaining <= l).findFirst().orElse(null);
            if (level != null && d.alert(level, Instant.now(clock))) {
                deadlines.save(d);
                businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_DEADLINE_APPROACHING, m)
                        .reference(DeadlineRecorder.ENTITY, d.getId()).attr("matterId", m.getId())
                        .attr("kind", d.getKind()).attr("level", level).attr("businessDaysRemaining", remaining)
                        .attr("dueDate", d.getDueDate().toString()).attr("responsibleUserId", d.getResponsibleUserId())
                        .attr("lawFirmId", m.getLawFirmId()));
                publish(d, m, remaining, false);
                metrics.deadlineAlert(level);
            }
        }
        return batch.size() < size ? null : batch.getLast().getId();
    }

    private void publish(LegalDeadline d, LegalMatter m, int remaining, boolean missed) {
        events.publishEvent(new LegalDeadlineApproaching(Ids.newId(), d.getTenantId(), Instant.now(clock), d.getId(),
                m.getId(), m.getCreditorId(), d.getResponsibleUserId(), m.getLawFirmId(), d.getKind().name(),
                d.getDueDate(), remaining, missed));
    }
}
