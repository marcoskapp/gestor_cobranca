package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases;
import br.com.gestorcobranca.legal.domain.model.CountingMode;
import br.com.gestorcobranca.legal.domain.model.CourtSuspension;
import br.com.gestorcobranca.legal.domain.model.DeadlineStatus;
import br.com.gestorcobranca.legal.domain.model.LegalDeadline;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.StartEvent;
import br.com.gestorcobranca.legal.domain.repository.CourtSuspensionRepository;
import br.com.gestorcobranca.legal.domain.repository.DeadlineSearchCriteria;
import br.com.gestorcobranca.legal.domain.repository.LegalDeadlineRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalMatterRepository;
import br.com.gestorcobranca.legal.domain.service.DeadlineCalculator;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
class LegalDeadlineService implements LegalDeadlineUseCases {

    private static final String SUSPENSION = "court_suspension";

    private final LegalDeadlineRepository deadlines;
    private final CourtSuspensionRepository suspensions;
    private final LegalMatterRepository matters;
    private final DeadlineRecorder recorder;
    private final DeadlineCommands commands;
    private final ForensicCalendars calendars;
    private final MatterRecorder matterRecorder;
    private final LegalAccess access;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final LegalMetrics metrics;
    private final Clock clock;

    LegalDeadlineService(LegalDeadlineRepository deadlines, CourtSuspensionRepository suspensions,
                         LegalMatterRepository matters, DeadlineRecorder recorder, DeadlineCommands commands,
                         ForensicCalendars calendars, MatterRecorder matterRecorder, LegalAccess access,
                         AccessScope accessScope, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                         LegalMetrics metrics, Clock clock) {
        this.deadlines = deadlines;
        this.suspensions = suspensions;
        this.matters = matters;
        this.recorder = recorder;
        this.commands = commands;
        this.calendars = calendars;
        this.matterRecorder = matterRecorder;
        this.access = access;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public DeadlineView create(UUID matterId, DeadlineCommand command) {
        LegalMatter m = access.forCommand(matterId);
        LegalDeadline deadline = recorder.create(m, commands.spec(m, command, null, null), "manual");
        return view(deadline, LegalViews.today(clock));
    }

    @Override
    @Transactional(readOnly = true)
    public DueDatePreview preview(DueDateQuery q) {
        CountingMode mode = q.countingMode() == null || q.countingMode().isBlank() ? CountingMode.BUSINESS_DAYS
                : LegalViews.parse(CountingMode.class, q.countingMode(), "legal_deadline.mode_invalid", "Contagem");
        StartEvent event = q.startEvent() == null || q.startEvent().isBlank() ? StartEvent.INTIMATION
                : LegalViews.parse(StartEvent.class, q.startEvent(), "legal_deadline.start_event_invalid", "Fato inicial");
        Guard.require(q.courtCode() == null || q.courtCode().matches("[1-9]\\.\\d{2}"), "legal_deadline.court_invalid",
                "Tribunal no formato J.TR");
        LocalDate due = calendars.dueDate(q.startDate(), event, q.termDays(), mode, q.fixedDate(), q.courtCode());
        LocalDate from = mode == CountingMode.FIXED_DATE ? q.fixedDate() : q.startDate();
        DeadlineCalculator.ForensicCalendar calendar = calendars.between(from, due, q.courtCode());
        List<LocalDate> nonBusiness = new ArrayList<>();
        for (LocalDate d = from.plusDays(1); !d.isAfter(due); d = d.plusDays(1)) {
            boolean business = mode == CountingMode.BUSINESS_DAYS ? calendar.isBusinessDay(d) : calendar.isOpenDay(d);
            if (!business) {
                nonBusiness.add(d);
            }
        }
        return new DueDatePreview(due, nonBusiness);
    }

    @Override
    public DeadlineView complete(UUID deadlineId, LocalDate completedOn, String note) {
        LegalDeadline d = load(deadlineId);
        LegalMatter m = access.forCommand(d.getMatterId());
        LocalDate today = LegalViews.today(clock);
        DeadlineStatus previous = d.complete(completedOn, today, note);
        deadlines.save(d);
        auditTrail.record(AuditEntry.of("legal_deadline.completed", DeadlineRecorder.ENTITY, d.getId())
                .creditor(d.getCreditorId()).reason(d.getStatusReason())
                .change("status", previous, d.getStatus()).meta("completedOn", d.getCompletedOn()));
        businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_DEADLINE_MET, m)
                .reference(DeadlineRecorder.ENTITY, d.getId())
                .attr("matterId", m.getId()).attr("kind", d.getKind()).attr("onTime", d.onTime())
                .attr("correctedFromMissed", previous == DeadlineStatus.MISSED)
                .attr("daysBeforeDue", java.time.temporal.ChronoUnit.DAYS.between(d.getCompletedOn(), d.getDueDate()))
                .attr("responsibleUserId", d.getResponsibleUserId()).attr("lawFirmId", m.getLawFirmId()));
        metrics.deadlineMet(d.onTime());
        return view(d, today);
    }

    @Override
    public DeadlineView cancel(UUID deadlineId, String reason) {
        LegalDeadline d = load(deadlineId);
        access.forCommand(d.getMatterId());
        d.cancel(reason);
        deadlines.save(d);
        auditTrail.record(AuditEntry.of("legal_deadline.cancelled", DeadlineRecorder.ENTITY, d.getId())
                .creditor(d.getCreditorId()).reason(d.getStatusReason())
                .change("status", DeadlineStatus.PENDING, DeadlineStatus.CANCELLED));
        return view(d, LegalViews.today(clock));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeadlineView> listByMatter(UUID matterId) {
        LegalMatter m = access.visible(matterId);
        LocalDate today = LegalViews.today(clock);
        return deadlines.findByMatterIdOrderByDueDateAscCreatedAtAsc(m.getId()).stream().map(d -> view(d, today)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DeadlineView> agenda(AgendaFilter f, PageQuery page) {
        if (f.matterId() != null) {
            access.visible(f.matterId());
        }
        DeadlineSearchCriteria criteria = new DeadlineSearchCriteria(access.creditorFilter(),
                access.firmFilter().orElse(null), f.matterId(),
                LegalViews.parseSet(DeadlineStatus.class, f.statuses(), "legal_deadline.status_invalid", "Situação"),
                f.dueFrom(), f.dueTo(), f.responsibleUserId());
        LocalDate today = LegalViews.today(clock);
        return PageResponse.of(deadlines.search(criteria, page.toPageable(Sort.by("dueDate").and(Sort.by("id")))),
                d -> view(d, today));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SuspensionView> suspensions() {
        return suspensions.findAllByOrderByStartDateDesc().stream().map(LegalViews::suspension).toList();
    }

    @Override
    public SuspensionView addSuspension(SuspensionCommand command) {
        access.requireInternal(SUSPENSION);
        accessScope.requireAllCreditors(SUSPENSION);
        CourtSuspension suspension = suspensions.save(new CourtSuspension(command.startDate(), command.endDate(),
                command.courtCode() == null || command.courtCode().isBlank() ? null : command.courtCode().trim(),
                command.reason()));
        int rescheduled = recomputePending("suspension");
        auditTrail.record(AuditEntry.of("court_suspension.created", SUSPENSION, suspension.getId())
                .created("startDate", suspension.getStartDate())
                .created("endDate", suspension.getEndDate())
                .created("courtCode", suspension.getCourtCode())
                .created("reason", suspension.getReason())
                .meta("deadlinesRescheduled", rescheduled));
        return LegalViews.suspension(suspension);
    }

    @Override
    public void removeSuspension(UUID suspensionId, String reason) {
        access.requireInternal(SUSPENSION);
        accessScope.requireAllCreditors(SUSPENSION);
        Guard.require(reason != null && !reason.isBlank(), "legal_suspension.reason_required", "Motivo obrigatório");
        CourtSuspension suspension = suspensions.findById(suspensionId)
                .orElseThrow(() -> ResourceNotFoundException.of(SUSPENSION, suspensionId));
        suspensions.delete(suspension);
        int rescheduled = recomputePending("suspension");
        auditTrail.record(AuditEntry.of("court_suspension.removed", SUSPENSION, suspensionId).reason(reason.trim())
                .change("startDate", suspension.getStartDate(), null)
                .change("endDate", suspension.getEndDate(), null)
                .change("courtCode", suspension.getCourtCode(), null)
                .meta("deadlinesRescheduled", rescheduled));
    }

    /**
     * Recalcula o vencimento dos prazos pendentes com o calendário atual (suspensões, feriados,
     * recesso), em lotes por cursor. Usado na mudança de suspensões e na varredura diária.
     *
     * @return prazos com vencimento alterado
     */
    int recomputePending(String trigger) {
        int changed = 0;
        UUID cursor = new UUID(0L, 0L);
        List<LegalDeadline> batch;
        do {
            batch = deadlines.findByStatusAndIdGreaterThanOrderByIdAsc(DeadlineStatus.PENDING, cursor, PageRequest.of(0, 200));
            for (LegalDeadline d : batch) {
                cursor = d.getId();
                LocalDate previous = d.getDueDate();
                if (d.reschedule(calendars.recompute(d))) {
                    deadlines.save(d);
                    auditTrail.record(AuditEntry.of("legal_deadline.rescheduled", DeadlineRecorder.ENTITY, d.getId())
                            .creditor(d.getCreditorId()).change("dueDate", previous, d.getDueDate())
                            .meta("trigger", trigger));
                    changed++;
                }
            }
        } while (batch.size() == 200);
        metrics.deadlinesRescheduled(trigger, changed);
        return changed;
    }

    private DeadlineView view(LegalDeadline d, LocalDate today) {
        return LegalViews.deadline(d, d.getStatus() == DeadlineStatus.PENDING
                ? calendars.remaining(today, d.getDueDate(), d.getCourtCode()) : null);
    }

    private LegalDeadline load(UUID deadlineId) {
        return deadlines.findById(deadlineId)
                .orElseThrow(() -> ResourceNotFoundException.of(DeadlineRecorder.ENTITY, deadlineId));
    }
}
