package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.domain.model.CountingMode;
import br.com.gestorcobranca.legal.domain.model.DeadlineKind;
import br.com.gestorcobranca.legal.domain.model.DeadlineStatus;
import br.com.gestorcobranca.legal.domain.model.ExtrajudicialMeasure;
import br.com.gestorcobranca.legal.domain.model.LegalDeadline;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.MeasureStatus;
import br.com.gestorcobranca.legal.domain.model.StartEvent;
import br.com.gestorcobranca.legal.domain.repository.ExtrajudicialMeasureRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalDeadlineRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Criação de prazos comum ao cadastro manual, aos andamentos e às reações automáticas (prazo de
 * baixa de protesto/negativação após o pagamento, prazo para protocolar acordo para homologação):
 * vencimento pelo calendário forense, auditoria, evento de negócio e métrica — na transação do
 * chamador, que já validou o escopo.
 */
@Component
class DeadlineRecorder {

    static final String ENTITY = "legal_deadline";
    private static final long MAX_FIXED_DAYS = 730;

    private final LegalDeadlineRepository deadlines;
    private final ExtrajudicialMeasureRepository measures;
    private final ForensicCalendars calendars;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final LegalMetrics metrics;
    private final Clock clock;

    DeadlineRecorder(LegalDeadlineRepository deadlines, ExtrajudicialMeasureRepository measures,
                     ForensicCalendars calendars, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                     LegalMetrics metrics, Clock clock) {
        this.deadlines = deadlines;
        this.measures = measures;
        this.calendars = calendars;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Dados já convertidos do prazo. */
    record Spec(DeadlineKind kind, String description, UUID lawsuitId, UUID movementId, UUID measureId, String courtCode,
                LocalDate startDate, StartEvent startEvent, Integer termDays, CountingMode mode, LocalDate fixedDate,
                UUID responsibleUserId) {
    }

    LegalDeadline create(LegalMatter matter, Spec s, String source) {
        if (s.kind() != DeadlineKind.RESTRICTION_REMOVAL) {
            // a baixa de protesto/negativação é acompanhada mesmo depois do encerramento
            matter.requireOpen();
        }
        LocalDate today = LegalViews.today(clock);
        if (s.mode() == CountingMode.FIXED_DATE) {
            Guard.require(s.fixedDate() != null && !s.fixedDate().isBefore(today)
                            && ChronoUnit.DAYS.between(today, s.fixedDate()) <= MAX_FIXED_DAYS,
                    "legal_deadline.fixed_date_invalid", "Data do prazo deve estar entre hoje e dois anos");
        } else {
            Guard.require(s.startDate() != null && !s.startDate().isAfter(today), "legal_deadline.start_invalid",
                    "Data de início obrigatória e não futura");
        }
        LocalDate due = calendars.dueDate(s.startDate(), s.startEvent(), s.termDays(), s.mode(), s.fixedDate(),
                s.courtCode());
        UUID responsible = s.responsibleUserId() != null ? s.responsibleUserId() : matter.getResponsibleUserId();
        LegalDeadline deadline = deadlines.save(new LegalDeadline(matter.getId(), matter.getCreditorId(), s.lawsuitId(),
                s.movementId(), s.measureId(), s.kind(), s.description(), s.courtCode(), s.startDate(), s.startEvent(),
                s.termDays(), s.mode(), s.fixedDate(), due, responsible));
        auditTrail.record(AuditEntry.of("legal_deadline.created", ENTITY, deadline.getId())
                .creditor(matter.getCreditorId())
                .created("matterId", matter.getId())
                .created("kind", deadline.getKind())
                .created("countingMode", deadline.getCountingMode())
                .created("startDate", deadline.getStartDate())
                .created("termDays", deadline.getTermDays())
                .created("dueDate", deadline.getDueDate())
                .created("responsibleUserId", deadline.getResponsibleUserId())
                .meta("source", source));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.LEGAL_DEADLINE_CREATED)
                .creditor(matter.getCreditorId()).portfolio(matter.getPortfolioId()).debtor(matter.getDebtorId())
                .collectionCase(matter.getCollectionCaseId()).reference(ENTITY, deadline.getId())
                .attr("matterId", matter.getId()).attr("kind", deadline.getKind())
                .attr("countingMode", deadline.getCountingMode()).attr("termDays", deadline.getTermDays())
                .attr("calendarDays", ChronoUnit.DAYS.between(today, due)).attr("source", source)
                .attr("stage", matter.getStage()));
        metrics.deadlineCreated(deadline.getKind(), deadline.getCountingMode());
        return deadline;
    }

    /**
     * Prazos de baixa de protesto e negativação efetivos após o pagamento (Súmula 548/STJ: cinco
     * dias úteis, parâmetro da assessoria) — um por medida, sem repetir.
     */
    int removalDeadlines(LegalMatter matter, int businessDays, String source) {
        List<ExtrajudicialMeasure> effective = measures.findByMatterIdAndStatusIn(matter.getId(),
                EnumSet.of(MeasureStatus.EFFECTIVE)).stream().filter(m -> m.getType().requiresRemovalAfterPayment())
                .toList();
        List<LegalDeadline> existing = deadlines.findByMatterIdOrderByDueDateAscCreatedAtAsc(matter.getId());
        int created = 0;
        for (ExtrajudicialMeasure m : effective) {
            boolean already = existing.stream().anyMatch(d -> d.getKind() == DeadlineKind.RESTRICTION_REMOVAL
                    && m.getId().equals(d.getMeasureId()) && d.getStatus() != DeadlineStatus.CANCELLED);
            if (already) {
                continue;
            }
            create(matter, new Spec(DeadlineKind.RESTRICTION_REMOVAL,
                    m.getType() == br.com.gestorcobranca.legal.domain.model.MeasureType.PROTEST ? "Baixa do protesto após o pagamento"
                            : "Baixa da negativação após o pagamento",
                    null, null, m.getId(), null, LegalViews.today(clock), StartEvent.INTIMATION, businessDays,
                    CountingMode.BUSINESS_DAYS, null, null), source);
            created++;
        }
        return created;
    }

    /** Cancela os prazos pendentes do caso jurídico encerrado (exceto os de baixa de restrição). */
    int cancelPending(LegalMatter matter, String reason) {
        int cancelled = 0;
        for (LegalDeadline d : deadlines.findByMatterIdAndStatus(matter.getId(), DeadlineStatus.PENDING)) {
            if (d.getKind() == DeadlineKind.RESTRICTION_REMOVAL) {
                continue;
            }
            d.cancel(reason);
            deadlines.save(d);
            auditTrail.record(AuditEntry.of("legal_deadline.cancelled", ENTITY, d.getId())
                    .creditor(d.getCreditorId()).reason(reason)
                    .change("status", DeadlineStatus.PENDING, DeadlineStatus.CANCELLED));
            cancelled++;
        }
        return cancelled;
    }
}
