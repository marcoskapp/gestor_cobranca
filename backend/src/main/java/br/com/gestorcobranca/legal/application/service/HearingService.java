package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.legal.application.port.in.HearingUseCases;
import br.com.gestorcobranca.legal.domain.model.Hearing;
import br.com.gestorcobranca.legal.domain.model.HearingMode;
import br.com.gestorcobranca.legal.domain.model.HearingOutcome;
import br.com.gestorcobranca.legal.domain.model.HearingStatus;
import br.com.gestorcobranca.legal.domain.model.HearingType;
import br.com.gestorcobranca.legal.domain.model.Lawsuit;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.repository.HearingRepository;
import br.com.gestorcobranca.legal.domain.repository.LawsuitRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class HearingService implements HearingUseCases {

    static final String ENTITY = "hearing";

    private final HearingRepository hearings;
    private final LawsuitRepository lawsuits;
    private final UserDirectory users;
    private final MatterRecorder matterRecorder;
    private final LegalAccess access;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final LegalMetrics metrics;
    private final Clock clock;

    HearingService(HearingRepository hearings, LawsuitRepository lawsuits, UserDirectory users,
                   MatterRecorder matterRecorder, LegalAccess access, AuditTrail auditTrail,
                   BusinessEventLogger businessEvents, LegalMetrics metrics, Clock clock) {
        this.hearings = hearings;
        this.lawsuits = lawsuits;
        this.users = users;
        this.matterRecorder = matterRecorder;
        this.access = access;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public HearingView schedule(UUID lawsuitId, HearingCommand c) {
        Lawsuit lawsuit = lawsuits.findById(lawsuitId).orElseThrow(() -> ResourceNotFoundException.of(LawsuitService.ENTITY, lawsuitId));
        LegalMatter m = access.forCommand(lawsuit.getMatterId());
        m.requireOpen();
        Guard.require(c.scheduledAt() != null && c.scheduledAt().isAfter(Instant.now(clock)), "legal_hearing.date_invalid",
                "Audiência deve ser agendada para o futuro");
        requireParticipants(c.participantUserIds(), m);
        Hearing h = hearings.save(new Hearing(lawsuit.getId(), m.getId(), m.getCreditorId(),
                LegalViews.parse(HearingType.class, c.type(), "legal_hearing.type_invalid", "Tipo da audiência"),
                c.scheduledAt(), LegalViews.parse(HearingMode.class, c.mode(), "legal_hearing.mode_invalid", "Forma"),
                c.location(), c.link(), c.participantUserIds(), c.externalParticipants()));
        auditTrail.record(AuditEntry.of("hearing.scheduled", ENTITY, h.getId()).creditor(m.getCreditorId())
                .created("lawsuitId", lawsuit.getId()).created("type", h.getType())
                .created("scheduledAt", h.getScheduledAt()).created("mode", h.getMode()));
        businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_HEARING_SCHEDULED, m).reference(ENTITY, h.getId())
                .attr("matterId", m.getId()).attr("type", h.getType()).attr("mode", h.getMode())
                .attr("daysAhead", java.time.Duration.between(Instant.now(clock), h.getScheduledAt()).toDays()));
        metrics.hearing(HearingStatus.SCHEDULED);
        return LegalViews.hearing(h);
    }

    @Override
    public HearingView reschedule(UUID hearingId, HearingCommand c) {
        Hearing h = load(hearingId);
        LegalMatter m = access.forCommand(h.getMatterId());
        Instant previous = h.getScheduledAt();
        requireParticipants(c.participantUserIds(), m);
        Guard.require(c.scheduledAt() != null && c.scheduledAt().isAfter(Instant.now(clock)), "legal_hearing.date_invalid",
                "Audiência deve ser agendada para o futuro");
        h.reschedule(c.scheduledAt(), LegalViews.parse(HearingMode.class, c.mode(), "legal_hearing.mode_invalid", "Forma"),
                c.location(), c.link(), c.participantUserIds(), c.externalParticipants());
        hearings.save(h);
        auditTrail.record(AuditEntry.of("hearing.rescheduled", ENTITY, h.getId()).creditor(m.getCreditorId())
                .change("scheduledAt", previous, h.getScheduledAt()).meta("mode", h.getMode()));
        return LegalViews.hearing(h);
    }

    @Override
    public HearingView recordResult(UUID hearingId, String outcome, String notes) {
        Hearing h = load(hearingId);
        LegalMatter m = access.forCommand(h.getMatterId());
        h.recordResult(LegalViews.parse(HearingOutcome.class, outcome, "legal_hearing.outcome_invalid", "Resultado"), notes,
                Instant.now(clock));
        hearings.save(h);
        auditTrail.record(AuditEntry.of("hearing.held", ENTITY, h.getId()).creditor(m.getCreditorId())
                .change("status", HearingStatus.SCHEDULED, h.getStatus()).created("outcome", h.getOutcome()));
        businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_HEARING_HELD, m).reference(ENTITY, h.getId())
                .attr("matterId", m.getId()).attr("type", h.getType()).attr("outcome", h.getOutcome())
                .attr("rescheduleCount", h.getRescheduleCount()));
        metrics.hearing(HearingStatus.HELD);
        return LegalViews.hearing(h);
    }

    @Override
    public HearingView cancel(UUID hearingId, String reason) {
        Hearing h = load(hearingId);
        LegalMatter m = access.forCommand(h.getMatterId());
        h.cancel(reason);
        hearings.save(h);
        auditTrail.record(AuditEntry.of("hearing.cancelled", ENTITY, h.getId()).creditor(m.getCreditorId())
                .change("status", HearingStatus.SCHEDULED, h.getStatus()));
        metrics.hearing(HearingStatus.CANCELLED);
        return LegalViews.hearing(h);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HearingView> list(UUID lawsuitId) {
        Lawsuit lawsuit = lawsuits.findById(lawsuitId).orElseThrow(() -> ResourceNotFoundException.of(LawsuitService.ENTITY, lawsuitId));
        access.visible(lawsuit.getMatterId());
        return hearings.findByLawsuitIdOrderByScheduledAtAsc(lawsuit.getId()).stream().map(LegalViews::hearing).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<HearingView> listByMatter(UUID matterId) {
        LegalMatter matter = access.visible(matterId);
        return hearings.findByMatterIdOrderByScheduledAtAscIdAsc(matter.getId()).stream().map(LegalViews::hearing).toList();
    }

    private void requireParticipants(Set<UUID> ids, LegalMatter m) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<UserDirectory.UserSummary> found = users.findAll(ids);
        Guard.require(found.size() == ids.size() && found.stream().allMatch(u -> u.active()
                        && u.canAccessCreditor(m.getCreditorId())), "legal_hearing.participant_invalid",
                "Participante inexistente, inativo ou sem acesso ao credor");
    }

    private Hearing load(UUID id) {
        return hearings.findById(id).orElseThrow(() -> ResourceNotFoundException.of(ENTITY, id));
    }
}
