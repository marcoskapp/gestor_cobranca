package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases;
import br.com.gestorcobranca.incentive.domain.model.quality.ConductOccurrence;
import br.com.gestorcobranca.incentive.domain.model.quality.EvaluationReferenceType;
import br.com.gestorcobranca.incentive.domain.model.quality.OccurrenceType;
import br.com.gestorcobranca.incentive.domain.model.quality.QualityCriterion;
import br.com.gestorcobranca.incentive.domain.model.quality.QualityEvaluation;
import br.com.gestorcobranca.incentive.domain.model.quality.QualityForm;
import br.com.gestorcobranca.incentive.domain.repository.QualityRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class QualityService implements QualityUseCases {

    private static final long MAX_RANGE_DAYS = 400;

    private final QualityRepository quality;
    private final Workforce workforce;
    private final IncentiveAccess access;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final Clock clock;

    QualityService(QualityRepository quality, Workforce workforce, IncentiveAccess access, AuditTrail auditTrail,
                   BusinessEventLogger businessEvents, Clock clock) {
        this.quality = quality;
        this.workforce = workforce;
        this.access = access;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.clock = clock;
    }

    @Override
    public FormView createForm(FormCommand command) {
        access.requirePermission(Permission.QUALITY_EVALUATE);
        List<QualityCriterion> criteria = command.criteria() == null ? List.of() : command.criteria().stream()
                .map(c -> new QualityCriterion(c.code(), c.description(), c.weight(), c.critical())).toList();
        QualityForm form = quality.saveForm(new QualityForm(command.name(), command.description(), criteria));
        auditTrail.record(AuditEntry.of("quality_form.created", "quality_form", form.getId())
                .created("name", form.getName()).created("criteria", criteria.size()));
        return formView(form);
    }

    @Override
    public FormView deactivateForm(UUID formId) {
        access.requirePermission(Permission.QUALITY_EVALUATE);
        QualityForm form = quality.findForm(formId).orElseThrow(() -> ResourceNotFoundException.of("quality_form", formId));
        boolean before = form.isActive();
        form.deactivate();
        quality.saveForm(form);
        auditTrail.record(AuditEntry.of("quality_form.deactivated", "quality_form", formId).change("active", before, false));
        return formView(form);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FormView> forms() {
        access.scope();
        return quality.findForms().stream().map(QualityService::formView).toList();
    }

    @Override
    public EvaluationView evaluate(EvaluationCommand command) {
        access.requirePermission(Permission.QUALITY_EVALUATE);
        Guard.require(command.userId() != null, "quality_evaluation.user_required", "Informe o negociador avaliado");
        access.requireVisible(command.userId(), "quality_evaluation");
        Guard.require(workforce.member(command.userId()).isPresent(), "quality_evaluation.user_not_found",
                "Negociador inexistente");
        QualityForm form = quality.findForm(command.formId())
                .orElseThrow(() -> ResourceNotFoundException.of("quality_form", command.formId()));
        EvaluationReferenceType referenceType = StatementService.enumOf(EvaluationReferenceType.class,
                command.referenceType() == null ? "" : command.referenceType(), "quality_evaluation.reference_invalid");
        QualityEvaluation evaluation = quality.saveEvaluation(new QualityEvaluation(form, command.userId(),
                access.actor().id(), referenceType, command.referenceId(), command.evaluatedOn(), command.scores(),
                command.comments(), today()));
        auditTrail.record(AuditEntry.of("quality_evaluation.registered", "quality_evaluation", evaluation.getId())
                .created("userId", evaluation.getEvaluatedUserId()).created("formId", form.getId())
                .created("score", evaluation.getScore()).created("criticalFailure", evaluation.isCriticalFailure()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.QUALITY_EVALUATED)
                .reference("quality_evaluation", evaluation.getId())
                .attr("userId", evaluation.getEvaluatedUserId()).attr("formId", form.getId())
                .attr("score", evaluation.getScore()).attr("criticalFailure", evaluation.isCriticalFailure())
                .attr("referenceType", referenceType));
        return evaluationView(evaluation);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EvaluationView> evaluations(UUID userId, LocalDate from, LocalDate to, PageQuery page) {
        LocalDate[] range = range(from, to);
        return PageResponse.of(quality.searchEvaluations(users(userId, "quality_evaluation"), range[0], range[1],
                page.toPageable()), QualityService::evaluationView);
    }

    @Override
    public OccurrenceView registerOccurrence(OccurrenceCommand command) {
        access.requirePermission(Permission.QUALITY_EVALUATE);
        Guard.require(command.userId() != null, "conduct.user_required", "Informe o negociador");
        access.requireVisible(command.userId(), "conduct_occurrence");
        Guard.require(workforce.member(command.userId()).isPresent(), "conduct.user_not_found", "Negociador inexistente");
        OccurrenceType type = StatementService.enumOf(OccurrenceType.class, command.type() == null ? "" : command.type(),
                "conduct.type_invalid");
        EvaluationReferenceType referenceType = command.referenceType() == null || command.referenceType().isBlank() ? null
                : StatementService.enumOf(EvaluationReferenceType.class, command.referenceType(), "conduct.reference_invalid");
        ConductOccurrence occurrence = quality.saveOccurrence(new ConductOccurrence(command.userId(), type,
                command.occurredOn(), command.description(), referenceType, command.referenceId(), access.actor().id(),
                today()));
        auditTrail.record(AuditEntry.of("conduct_occurrence.registered", "conduct_occurrence", occurrence.getId())
                .created("userId", occurrence.getUserId()).created("type", type)
                .created("occurredOn", occurrence.getOccurredOn()).reason(occurrence.getDescription()));
        return occurrenceView(occurrence);
    }

    @Override
    public OccurrenceView dismissOccurrence(UUID occurrenceId, String reason) {
        access.requirePermission(Permission.COMMISSION_STATEMENT_MANAGE);
        ConductOccurrence occurrence = quality.findOccurrence(occurrenceId)
                .orElseThrow(() -> ResourceNotFoundException.of("conduct_occurrence", occurrenceId));
        occurrence.dismiss(reason, access.actor().id(), Instant.now(clock));
        quality.saveOccurrence(occurrence);
        auditTrail.record(AuditEntry.of("conduct_occurrence.dismissed", "conduct_occurrence", occurrenceId)
                .change("status", ConductOccurrence.Status.CONFIRMED, ConductOccurrence.Status.DISMISSED)
                .reason(occurrence.getDismissalReason()));
        return occurrenceView(occurrence);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OccurrenceView> occurrences(UUID userId, LocalDate from, LocalDate to, PageQuery page) {
        LocalDate[] range = range(from, to);
        return PageResponse.of(quality.searchOccurrences(users(userId, "conduct_occurrence"), range[0], range[1],
                page.toPageable()), QualityService::occurrenceView);
    }

    private Set<UUID> users(UUID userId, String resource) {
        if (userId != null) {
            access.requireVisible(userId, resource);
            return Set.of(userId);
        }
        return access.visibleUsers();
    }

    private LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? today() : to;
        LocalDate start = from == null ? end.minusDays(90) : from;
        Guard.require(!end.isBefore(start), "quality.range_invalid", "Período inválido");
        Guard.require(ChronoUnit.DAYS.between(start, end) <= MAX_RANGE_DAYS, "quality.range_too_long",
                "Período máximo de 400 dias");
        return new LocalDate[]{start, end};
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    private static FormView formView(QualityForm f) {
        return new FormView(f.getId(), f.getName(), f.getDescription(), f.isActive(), f.getCriteria().stream()
                .map(c -> new CriterionInput(c.getCode(), c.getDescription(), c.getWeight(), c.isCritical())).toList());
    }

    private static EvaluationView evaluationView(QualityEvaluation e) {
        return new EvaluationView(e.getId(), e.getFormId(), e.getEvaluatedUserId(), e.getEvaluatorId(),
                e.getReferenceType().name(), e.getReferenceId(), e.getEvaluatedOn(), e.getScore(), e.isCriticalFailure(),
                e.getCriterionScores(), e.getComments(), e.getCreatedAt());
    }

    private static OccurrenceView occurrenceView(ConductOccurrence o) {
        return new OccurrenceView(o.getId(), o.getUserId(), o.getType().name(), o.getOccurredOn(), o.getDescription(),
                o.getReferenceType() == null ? null : o.getReferenceType().name(), o.getReferenceId(), o.getStatus().name(),
                o.getRegisteredBy(), o.getDismissedBy(), o.getDismissedAt(), o.getDismissalReason());
    }
}
