package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases.CollateralView;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.legal.application.port.in.ExtrajudicialMeasureUseCases;
import br.com.gestorcobranca.legal.application.port.out.CreditBureauProvider;
import br.com.gestorcobranca.legal.application.port.out.LegalProvider;
import br.com.gestorcobranca.legal.application.port.out.LegalProvider.SubmissionReceipt;
import br.com.gestorcobranca.legal.application.port.out.ProtestProvider;
import br.com.gestorcobranca.legal.application.port.out.RealEstateRegistryProvider;
import br.com.gestorcobranca.legal.application.port.out.VehicleLienProvider;
import br.com.gestorcobranca.legal.domain.model.DeadlineKind;
import br.com.gestorcobranca.legal.domain.model.DeadlineStatus;
import br.com.gestorcobranca.legal.domain.model.DocumentLink;
import br.com.gestorcobranca.legal.domain.model.ExtrajudicialMeasure;
import br.com.gestorcobranca.legal.domain.model.LegalDeadline;
import br.com.gestorcobranca.legal.domain.model.LegalDocument;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.LegalRoute;
import br.com.gestorcobranca.legal.domain.model.LegalStage;
import br.com.gestorcobranca.legal.domain.model.MeasureStatus;
import br.com.gestorcobranca.legal.domain.model.MeasureType;
import br.com.gestorcobranca.legal.domain.repository.ExtrajudicialMeasureRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalDeadlineRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalDocumentRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class ExtrajudicialMeasureService implements ExtrajudicialMeasureUseCases {

    static final String ENTITY = "extrajudicial_measure";
    private static final Set<String> ENFORCEABLE = Set.of("REAL_ESTATE_FIDUCIARY_ALIENATION", "MORTGAGE",
            "VEHICLE_FIDUCIARY_ALIENATION");

    private final ExtrajudicialMeasureRepository measures;
    private final LegalDocumentRepository documents;
    private final LegalDeadlineRepository deadlines;
    private final DebtTitleUseCases titles;
    private final DebtorUseCases debtors;
    private final CollateralStatusUpdater collaterals;
    private final PrescriptionInterruptions interruptions;
    private final LegalProviders providers;
    private final MatterRequirements requirements;
    private final MatterRecorder matterRecorder;
    private final LegalSettingsProvider settings;
    private final LegalAccess access;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final LegalMetrics metrics;
    private final Clock clock;

    ExtrajudicialMeasureService(ExtrajudicialMeasureRepository measures, LegalDocumentRepository documents,
                                LegalDeadlineRepository deadlines, DebtTitleUseCases titles, DebtorUseCases debtors,
                                CollateralStatusUpdater collaterals, PrescriptionInterruptions interruptions,
                                LegalProviders providers, MatterRequirements requirements, MatterRecorder matterRecorder,
                                LegalSettingsProvider settings, LegalAccess access, AccessScope accessScope,
                                AuditTrail auditTrail, BusinessEventLogger businessEvents, LegalMetrics metrics,
                                Clock clock) {
        this.measures = measures;
        this.documents = documents;
        this.deadlines = deadlines;
        this.titles = titles;
        this.debtors = debtors;
        this.collaterals = collaterals;
        this.interruptions = interruptions;
        this.providers = providers;
        this.requirements = requirements;
        this.matterRecorder = matterRecorder;
        this.settings = settings;
        this.access = access;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public MeasureView register(UUID matterId, MeasureCommand command) {
        LegalMatter m = access.forCommand(matterId);
        m.requireOpen();
        Guard.requireState(m.getStage().acceptsActions(), "legal_measure.stage_invalid",
                "Medidas extrajudiciais exigem a autorização do credor");
        MeasureType type = LegalViews.parse(MeasureType.class, command.type(), "legal_measure.type_invalid", "Tipo da medida");
        Set<LegalRoute> authorized = requirements.authorizedRoutes(m.getId());
        boolean covered = type.route() == null ? !authorized.isEmpty() : authorized.contains(type.route());
        Guard.requireState(covered, "legal_measure.not_authorized", "O credor não autorizou esta via");
        Set<UUID> titleIds = command.titleIds() == null || command.titleIds().isEmpty() ? m.getTitleIds()
                : Set.copyOf(command.titleIds());
        Guard.require(m.getTitleIds().containsAll(titleIds), "legal_measure.title_not_in_matter",
                "Os títulos da medida devem ser do caso jurídico");
        String collateralType = null;
        List<TitleView> measureTitles = titleIds.stream().map(titles::get).toList();
        if (command.collateralId() != null) {
            CollateralView collateral = collaterals.get(command.collateralId());
            Guard.require(collateral.creditorId().equals(m.getCreditorId()) && coversAny(collateral, measureTitles),
                    "legal_measure.collateral_not_in_matter", "A garantia não cobre os títulos do caso jurídico");
            Guard.require(type != MeasureType.COLLATERAL_ENFORCEMENT || ENFORCEABLE.contains(collateral.type()),
                    "legal_measure.collateral_not_enforceable",
                    "Execução extrajudicial só para alienação fiduciária ou hipoteca");
            Guard.requireState(Set.of("ACTIVE", "UNDER_ENFORCEMENT").contains(collateral.status()),
                    "legal_measure.collateral_status", "Garantia não está ativa");
            collateralType = collateral.type();
        }
        LocalDate today = LegalViews.today(clock);
        Guard.require(command.priorNoticeOn() == null || !command.priorNoticeOn().isAfter(today),
                "legal_measure.date_invalid", "Data da comunicação prévia não pode ser futura");
        Money amount = command.amount() != null ? Money.of(command.amount())
                : measureTitles.stream().map(t -> Money.ofNullable(t.openBalance())).reduce(Money.ZERO, Money::plus);
        ExtrajudicialMeasure measure = measures.save(new ExtrajudicialMeasure(m.getId(), m.getCreditorId(), type, titleIds,
                command.collateralId(), amount, command.authority(), command.priorNoticeOn(), command.dueDate(),
                providers.codeForMeasure(type, collateralType)));
        if (m.getStage() == LegalStage.AWAITING_AUTHORIZATION) {
            matterRecorder.changeStage(m, LegalStage.EXTRAJUDICIAL, requirements.of(m, null, null),
                    "Medida extrajudicial " + type.name() + " registrada", "measure");
        }
        auditTrail.record(AuditEntry.of("extrajudicial_measure.registered", ENTITY, measure.getId())
                .creditor(m.getCreditorId())
                .created("matterId", m.getId())
                .created("type", measure.getType())
                .created("titleIds", measure.getTitleIds().stream().sorted().toList().toString())
                .created("collateralId", measure.getCollateralId())
                .created("amount", measure.getAmount())
                .created("authority", measure.getAuthority())
                .created("provider", measure.getProvider()));
        businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_MEASURE_REGISTERED, m).amount(measure.getAmount())
                .attr("type", measure.getType()).attr("titles", measure.getTitleIds().size())
                .attr("collateralType", collateralType).attr("provider", measure.getProvider())
                .attr("stage", m.getStage()));
        metrics.measureTransition(type, MeasureStatus.PLANNED);
        return LegalViews.measure(measure);
    }

    @Override
    @Transactional(readOnly = true)
    public MeasureView get(UUID measureId) {
        ExtrajudicialMeasure measure = load(measureId);
        access.visible(measure.getMatterId());
        return LegalViews.measure(measure);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MeasureView> list(UUID matterId) {
        LegalMatter m = access.visible(matterId);
        return measures.findByMatterIdOrderByCreatedAtAsc(m.getId()).stream().map(LegalViews::measure).toList();
    }

    @Override
    public MeasureView submit(UUID measureId, SubmitCommand command) {
        ExtrajudicialMeasure measure = load(measureId);
        LegalMatter m = access.forCommand(measure.getMatterId());
        m.requireOpen();
        MeasureStatus previous = measure.submit(command.submittedOn(), command.protocol(), null, LegalViews.today(clock),
                settings.current().creditBureauNoticeDays());
        afterSubmit(m, measure, previous);
        return LegalViews.measure(measure);
    }

    @Override
    public MeasureView dispatch(UUID measureId) {
        ExtrajudicialMeasure measure = load(measureId);
        LegalMatter m = access.forCommand(measure.getMatterId());
        m.requireOpen();
        Guard.requireState(measure.getStatus() == MeasureStatus.PLANNED, "legal_measure.transition_invalid",
                "Somente medida planejada é enviada");
        CollateralView collateral = measure.getCollateralId() == null ? null : collaterals.get(measure.getCollateralId());
        LegalProvider provider = providers.forMeasure(measure.getType(), collateral == null ? null : collateral.type());
        providers.requireAutomatic(provider, measure.getType().name().toLowerCase(), "dispatch");
        accessScope.requirePermission(Permission.DEBTOR_SENSITIVE_VIEW);
        LocalDate today = LegalViews.today(clock);
        measure.checkSubmission(today, today, settings.current().creditBureauNoticeDays());
        SubmissionReceipt receipt = send(provider, measure, m, collateral);
        measure.registerProviderReference(provider.code(), receipt.reference());
        MeasureStatus previous = measure.submit(receipt.submittedOn() == null ? today : receipt.submittedOn(),
                receipt.protocol(), receipt.reference(), today, settings.current().creditBureauNoticeDays());
        afterSubmit(m, measure, previous);
        return LegalViews.measure(measure);
    }

    private SubmissionReceipt send(LegalProvider provider, ExtrajudicialMeasure measure, LegalMatter m,
                                   CollateralView collateral) {
        DebtorUseCases.RevealedDocument document = debtors.revealDocument(m.getDebtorId());
        String name = debtors.get(m.getDebtorId()).name();
        List<TitleView> measureTitles = measure.getTitleIds().stream().map(titles::get)
                .sorted(Comparator.comparing(TitleView::dueDate)).toList();
        return switch (measure.getType()) {
            case PROTEST -> ((ProtestProvider) provider).submit(new ProtestProvider.ProtestRequest(measure.getId(),
                    document.document(), name, measure.getAmount().amount(), measureTitles.stream()
                    .map(t -> new ProtestProvider.ProtestedTitle(t.externalId(), null, t.dueDate(), t.openBalance()))
                    .toList()));
            case CREDIT_BUREAU_LISTING -> ((CreditBureauProvider) provider).include(new CreditBureauProvider.ListingRequest(
                    measure.getId(), document.document(), name, measure.getAmount().amount(),
                    measureTitles.getFirst().dueDate(), measure.getPriorNoticeOn()));
            case COLLATERAL_ENFORCEMENT -> provider instanceof VehicleLienProvider vehicle
                    ? vehicle.startEnforcement(new VehicleLienProvider.VehicleEnforcementRequest(measure.getId(),
                    collateral.asset().plate(), collateral.asset().renavam(), collateral.asset().chassis(),
                    document.document(), name, measure.getAmount().amount()))
                    : ((RealEstateRegistryProvider) provider).startEnforcement(
                    new RealEstateRegistryProvider.RealEstateEnforcementRequest(measure.getId(), collateral.type(),
                            collateral.asset().registryNumber(), collateral.asset().registryOffice(),
                            collateral.asset().municipality(), collateral.asset().state(), document.document(), name,
                            measure.getAmount().amount()));
            case NOTICE -> throw new BusinessRuleException("legal_measure.no_provider", "Notificação não tem provedor");
        };
    }

    private void afterSubmit(LegalMatter m, ExtrajudicialMeasure measure, MeasureStatus previous) {
        measures.save(measure);
        if (measure.getType() == MeasureType.COLLATERAL_ENFORCEMENT) {
            collaterals.changeIf(measure.getCollateralId(), "ACTIVE", "UNDER_ENFORCEMENT",
                    "Execução extrajudicial iniciada (medida " + measure.getId() + ")");
        }
        recordTransition(m, measure, previous, null);
    }

    @Override
    public MeasureView changeStatus(UUID measureId, StatusCommand command) {
        ExtrajudicialMeasure measure = load(measureId);
        LegalMatter m = access.forCommand(measure.getMatterId());
        MeasureStatus target = LegalViews.parse(MeasureStatus.class, command.status(), "legal_measure.status_invalid",
                "Situação da medida");
        LocalDate today = LegalViews.today(clock);
        if (target != MeasureStatus.REMOVED) {
            m.requireOpen();
        }
        MeasureStatus previous;
        int interrupted = 0;
        if (target == MeasureStatus.EFFECTIVE) {
            previous = measure.markEffective(command.date(), today, command.reason());
            measures.save(measure);
            interrupted = effective(m, measure);
        } else {
            previous = measure.resolve(target, command.date(), today, command.reason());
            measures.save(measure);
            if (measure.getType() == MeasureType.COLLATERAL_ENFORCEMENT && target != MeasureStatus.REMOVED) {
                collaterals.changeIf(measure.getCollateralId(), "UNDER_ENFORCEMENT", "ACTIVE",
                        "Execução extrajudicial encerrada sem efetivação (" + target.name() + ")");
            }
            if (target == MeasureStatus.REMOVED) {
                completeRemovalDeadline(measure, measure.getResolvedOn());
            }
        }
        recordTransition(m, measure, previous, interrupted);
        return LegalViews.measure(measure);
    }

    /** Efeitos da efetivação: interrupção da prescrição (protesto) e situação da garantia (execução). */
    private int effective(LegalMatter m, ExtrajudicialMeasure measure) {
        if (measure.getType() == MeasureType.PROTEST) {
            return interruptions.register(measure.getTitleIds(), "PROTEST", measure.getEffectiveOn(),
                    measure.getProtocol() == null ? "Protesto" : "Protesto " + measure.getProtocol(), measure.getId(),
                    evidenceOf(measure));
        }
        if (measure.getType() == MeasureType.COLLATERAL_ENFORCEMENT) {
            CollateralView collateral = collaterals.get(measure.getCollateralId());
            String target = switch (collateral.type()) {
                case "REAL_ESTATE_FIDUCIARY_ALIENATION" -> "CONSOLIDATED";
                case "VEHICLE_FIDUCIARY_ALIENATION" -> "SEIZED";
                default -> null;
            };
            if (target != null) {
                collaterals.changeIf(measure.getCollateralId(), "UNDER_ENFORCEMENT", target,
                        "Execução extrajudicial efetivada (medida " + measure.getId() + ")");
            }
        }
        return 0;
    }

    private LegalDocument evidenceOf(ExtrajudicialMeasure measure) {
        return documents.findByMatterIdOrderByCreatedAtDesc(measure.getMatterId()).stream()
                .filter(d -> d.getLinkType() == DocumentLink.MEASURE && measure.getId().equals(d.getLinkId()))
                .findFirst().orElse(null);
    }

    private void completeRemovalDeadline(ExtrajudicialMeasure measure, LocalDate on) {
        for (LegalDeadline d : deadlines.findByMatterIdAndStatus(measure.getMatterId(), DeadlineStatus.PENDING)) {
            if (d.getKind() == DeadlineKind.RESTRICTION_REMOVAL && measure.getId().equals(d.getMeasureId())) {
                d.complete(on, LegalViews.today(clock), "Baixa registrada na medida");
                deadlines.save(d);
                auditTrail.record(AuditEntry.of("legal_deadline.completed", DeadlineRecorder.ENTITY, d.getId())
                        .creditor(d.getCreditorId()).change("status", DeadlineStatus.PENDING, d.getStatus())
                        .meta("completedOn", on).meta("cause", "measure_removed"));
                metrics.deadlineMet(d.onTime());
            }
        }
    }

    private void recordTransition(LegalMatter m, ExtrajudicialMeasure measure, MeasureStatus previous, Integer interrupted) {
        auditTrail.record(AuditEntry.of("extrajudicial_measure.status_changed", ENTITY, measure.getId())
                .creditor(m.getCreditorId()).reason(measure.getStatusReason())
                .change("status", previous, measure.getStatus())
                .meta("protocol", measure.getProtocol())
                .meta("submittedOn", measure.getSubmittedOn())
                .meta("effectiveOn", measure.getEffectiveOn())
                .meta("resolvedOn", measure.getResolvedOn())
                .meta("prescriptionInterruptions", interrupted));
        businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_MEASURE_STATUS_CHANGED, m)
                .amount(measure.getAmount()).reference(ENTITY, measure.getId())
                .attr("matterId", m.getId()).attr("type", measure.getType()).attr("from", previous)
                .attr("to", measure.getStatus()).attr("provider", measure.getProvider())
                .attr("prescriptionInterruptions", interrupted)
                .attr("daysSinceSubmission", measure.getSubmittedOn() == null ? null
                        : java.time.temporal.ChronoUnit.DAYS.between(measure.getSubmittedOn(), LegalViews.today(clock))));
        metrics.measureTransition(measure.getType(), measure.getStatus());
    }

    /** A garantia cobre algum título da medida (todos os títulos do contrato, ou os vinculados). */
    private static boolean coversAny(CollateralView collateral, List<TitleView> measureTitles) {
        return measureTitles.stream().anyMatch(t -> collateral.contractId().equals(t.originContractId())
                && (collateral.titleIds().isEmpty() || collateral.titleIds().contains(t.id())));
    }

    private ExtrajudicialMeasure load(UUID measureId) {
        return measures.findById(measureId).orElseThrow(() -> ResourceNotFoundException.of(ENTITY, measureId));
    }
}
