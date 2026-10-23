package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.CampaignProperties;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases;
import br.com.gestorcobranca.campaign.application.port.out.CampaignPanelQueries;
import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.Cadence;
import br.com.gestorcobranca.campaign.domain.model.CadenceStatus;
import br.com.gestorcobranca.campaign.domain.model.Campaign;
import br.com.gestorcobranca.campaign.domain.model.CampaignStatus;
import br.com.gestorcobranca.campaign.domain.model.EndReason;
import br.com.gestorcobranca.campaign.domain.model.Enrollment;
import br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus;
import br.com.gestorcobranca.campaign.domain.model.PauseReason;
import br.com.gestorcobranca.campaign.domain.repository.CadenceRepository;
import br.com.gestorcobranca.campaign.domain.repository.EnrollmentRepository;
import br.com.gestorcobranca.campaign.domain.repository.SegmentRepository;
import br.com.gestorcobranca.campaign.domain.repository.StepExecutionRepository;
import br.com.gestorcobranca.campaign.domain.service.PauseRules;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
class ExecutionService implements CampaignExecutionUseCases {

    private final EnrollmentRepository enrollments;
    private final CadenceRepository cadences;
    private final SegmentRepository segments;
    private final StepExecutionRepository executions;
    private final CampaignAccess access;
    private final AccessScope accessScope;
    private final CampaignViews views;
    private final EnrollmentLifecycle lifecycle;
    private final CampaignMaintenance maintenance;
    private final AudienceProjection projection;
    private final CampaignPanelQueries panels;
    private final CampaignEvents events;
    private final CampaignMetrics metrics;
    private final JobQueue jobQueue;
    private final AuditTrail audit;
    private final CampaignProperties properties;
    private final Clock clock;

    ExecutionService(EnrollmentRepository enrollments, CadenceRepository cadences, SegmentRepository segments,
                     StepExecutionRepository executions, CampaignAccess access, AccessScope accessScope,
                     CampaignViews views, EnrollmentLifecycle lifecycle, CampaignMaintenance maintenance,
                     AudienceProjection projection, CampaignPanelQueries panels, CampaignEvents events,
                     CampaignMetrics metrics, JobQueue jobQueue, AuditTrail audit, CampaignProperties properties,
                     Clock clock) {
        this.enrollments = enrollments;
        this.cadences = cadences;
        this.segments = segments;
        this.executions = executions;
        this.access = access;
        this.accessScope = accessScope;
        this.views = views;
        this.lifecycle = lifecycle;
        this.maintenance = maintenance;
        this.projection = projection;
        this.panels = panels;
        this.events = events;
        this.metrics = metrics;
        this.jobQueue = jobQueue;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public CampaignExecutionResult activate(UUID campaignId) {
        Campaign campaign = access.campaignForCommand(campaignId);
        Cadence cadence = cadences.findByCampaignIdAndStatus(campaignId, CadenceStatus.PUBLISHED)
                .orElseThrow(() -> new ConflictException("campaign.no_published_cadence",
                        "Publique a régua antes de ativar a campanha"));
        Guard.requireState(segments.findById(campaign.getSegmentId()).map(s -> s.isActive()).orElse(false),
                "campaign.segment_inactive", "Segmento inativo");
        CampaignStatus before = campaign.getStatus();
        campaign.activate(Instant.now(clock), access.actor().id());
        int rescheduled = before == CampaignStatus.PAUSED ? maintenance.rescheduleAll(campaignId) : 0;
        jobQueue.enqueue(EnrollJob.TYPE, new EnrollJob.Payload(campaignId, null), null, EnrollJob.dedupKey(campaignId, null));
        audit.record(AuditEntry.of("campaign.activated", "campaign", campaignId).creditor(campaign.getCreditorId())
                .change("status", before, campaign.getStatus()).meta("cadenceVersion", cadence.getVersionNumber()));
        events.activated(campaign, cadence.getVersionNumber(), before == CampaignStatus.PAUSED);
        return new CampaignExecutionResult(campaignId, campaign.getStatus().name(), rescheduled);
    }

    @Override
    public CampaignExecutionResult pause(UUID campaignId, String note) {
        Campaign campaign = access.campaignForCommand(campaignId);
        campaign.pause(note, Instant.now(clock));
        audit.record(AuditEntry.of("campaign.paused", "campaign", campaignId).creditor(campaign.getCreditorId())
                .change("status", CampaignStatus.ACTIVE, campaign.getStatus()).reason(campaign.getPauseNote()));
        events.campaignPaused(campaign);
        metrics.pause("campaign", "MANUAL");
        return new CampaignExecutionResult(campaignId, campaign.getStatus().name(), 0);
    }

    @Override
    public CampaignExecutionResult finish(UUID campaignId, String note) {
        Guard.require(note != null && !note.isBlank() && note.length() <= 300, "campaign.finish_note_required",
                "Informe o motivo do encerramento (até 300)");
        Campaign campaign = access.campaignForCommand(campaignId);
        CampaignStatus before = campaign.getStatus();
        campaign.finish(Instant.now(clock));
        int ended = maintenance.finishEnrollments(campaign, null, properties.maintenanceBatch());
        audit.record(AuditEntry.of("campaign.finished", "campaign", campaignId).creditor(campaign.getCreditorId())
                .change("status", before, campaign.getStatus()).reason(note.trim()));
        events.campaignFinished(campaign, ended);
        return new CampaignExecutionResult(campaignId, campaign.getStatus().name(), ended);
    }

    @Override
    public CampaignExecutionResult reprocess(UUID campaignId) {
        Campaign campaign = access.campaignForCommand(campaignId);
        Guard.requireState(campaign.getStatus() != CampaignStatus.FINISHED, "campaign.finished", "Campanha encerrada");
        Instant now = Instant.now(clock);
        List<UUID> ids = enrollments.findIdsByCampaignAndStatus(campaignId, EnumSet.of(EnrollmentStatus.FAILED), null,
                properties.maintenanceBatch());
        for (UUID id : ids) {
            Enrollment e = enrollments.findByIdForUpdate(id).orElseThrow();
            if (e.getStatus() == EnrollmentStatus.FAILED) {
                e.reprocess(now);
                enrollments.save(e);
                lifecycle.schedule(e);
            }
        }
        audit.record(AuditEntry.of("campaign.reprocessed", "campaign", campaignId).creditor(campaign.getCreditorId())
                .meta("enrollments", ids.size()));
        return new CampaignExecutionResult(campaignId, campaign.getStatus().name(), ids.size());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EnrollmentView> enrollments(UUID campaignId, EnrollmentFilter filter, PageQuery page) {
        access.visibleCampaign(campaignId);
        EnrollmentFilter f = filter == null ? new EnrollmentFilter(null, null, null) : filter;
        EnrollmentStatus status = f.status() == null ? null
                : CampaignService.parse(EnrollmentStatus.class, f.status(), "enrollment.status_invalid");
        return PageResponse.of(enrollments.search(campaignId, status, f.debtorId(), f.caseId(),
                page.toPageable(Sort.by(Sort.Direction.DESC, "enrolledAt"))), views::enrollment);
    }

    @Override
    @Transactional(readOnly = true)
    public EnrollmentDetail enrollment(UUID enrollmentId) {
        Enrollment e = access.visibleEnrollment(enrollmentId);
        return new EnrollmentDetail(views.enrollment(e),
                executions.findByEnrollmentIdOrderByExecutedAt(enrollmentId).stream().map(views::step).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EnrollmentView> byCase(UUID caseId) {
        return enrollments.findByCaseId(caseId).stream().filter(e -> accessScope.canAccessCreditor(e.getCreditorId()))
                .map(views::enrollment).toList();
    }

    @Override
    public EnrollmentView pauseEnrollment(UUID enrollmentId, String note) {
        Guard.require(note != null && !note.isBlank() && note.length() <= 300, "enrollment.pause_note_required",
                "Informe o motivo da pausa (até 300)");
        Enrollment e = access.enrollmentForCommand(enrollmentId);
        Guard.requireState(e.getStatus() == EnrollmentStatus.ACTIVE || e.getStatus() == EnrollmentStatus.FAILED,
                "enrollment.not_active", "Inscrição não está ativa");
        Campaign campaign = access.visibleCampaign(e.getCampaignId());
        EnrollmentStatus before = e.getStatus();
        lifecycle.pause(e, campaign, PauseReason.MANUAL, null, "manual");
        audit.record(AuditEntry.of("campaign_enrollment.paused", "campaign_enrollment", enrollmentId)
                .creditor(e.getCreditorId()).change("status", before, e.getStatus()).reason(note.trim()));
        return views.enrollment(e);
    }

    @Override
    public EnrollmentView resumeEnrollment(UUID enrollmentId) {
        Enrollment e = access.enrollmentForCommand(enrollmentId);
        Guard.requireState(e.getStatus() == EnrollmentStatus.PAUSED, "enrollment.not_paused", "Inscrição não está pausada");
        Campaign campaign = access.visibleCampaign(e.getCampaignId());
        AudienceSnapshot snapshot = projection.refresh(e.getCaseId(), "manual_resume");
        Guard.requireState(snapshot.active(), "enrollment.case_closed", "Caso encerrado");
        PauseRules.blocking(snapshot, campaign.getPurpose()).ifPresent(reason -> {
            throw new ConflictException("enrollment.still_blocked",
                    "A situação do caso ainda impede a régua: " + reason.name());
        });
        PauseReason before = e.getPauseReason();
        lifecycle.resume(e, "manual");
        audit.record(AuditEntry.of("campaign_enrollment.resumed", "campaign_enrollment", enrollmentId)
                .creditor(e.getCreditorId()).change("status", EnrollmentStatus.PAUSED, e.getStatus())
                .meta("pauseReason", before));
        return views.enrollment(e);
    }

    @Override
    public EnrollmentView stopEnrollment(UUID enrollmentId, String note) {
        Guard.require(note != null && !note.isBlank() && note.length() <= 300, "enrollment.stop_note_required",
                "Informe o motivo (até 300)");
        Enrollment e = access.enrollmentForCommand(enrollmentId);
        Guard.requireState(e.isRunning() || e.getStatus() == EnrollmentStatus.HOLDOUT, "enrollment.ended",
                "Inscrição já encerrada");
        Campaign campaign = access.visibleCampaign(e.getCampaignId());
        EnrollmentStatus before = e.getStatus();
        lifecycle.end(e, campaign, EndReason.MANUAL);
        audit.record(AuditEntry.of("campaign_enrollment.stopped", "campaign_enrollment", enrollmentId)
                .creditor(e.getCreditorId()).change("status", before, e.getStatus()).reason(note.trim()));
        return views.enrollment(e);
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignPanel panel(UUID campaignId) {
        access.visibleCampaign(campaignId);
        return panels.panel(campaignId, Instant.now(clock));
    }
}
