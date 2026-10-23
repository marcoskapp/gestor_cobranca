package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases.CollateralView;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.ObligationPartyUseCases;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.DeadlineView;
import br.com.gestorcobranca.legal.application.port.out.CourtDataProvider;
import br.com.gestorcobranca.legal.application.port.out.CourtDataProvider.CapturedMovement;
import br.com.gestorcobranca.legal.application.port.out.CourtDataProvider.CourtCapture;
import br.com.gestorcobranca.legal.domain.model.CaseMovement;
import br.com.gestorcobranca.legal.domain.model.CnjNumber;
import br.com.gestorcobranca.legal.domain.model.LawsuitStatus;
import br.com.gestorcobranca.legal.domain.model.Lawsuit;
import br.com.gestorcobranca.legal.domain.model.LegalDeadline;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.LegalRoute;
import br.com.gestorcobranca.legal.domain.model.LegalStage;
import br.com.gestorcobranca.legal.domain.model.LitigationPole;
import br.com.gestorcobranca.legal.domain.model.MovementSource;
import br.com.gestorcobranca.legal.domain.model.MovementType;
import br.com.gestorcobranca.legal.domain.repository.CaseMovementRepository;
import br.com.gestorcobranca.legal.domain.repository.LawsuitRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalMatterRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class LawsuitService implements LawsuitUseCases {

    static final String ENTITY = "lawsuit";
    static final String MOVEMENT = "case_movement";
    private static final Logger LOG = LoggerFactory.getLogger(LawsuitService.class);

    private final LawsuitRepository lawsuits;
    private final CaseMovementRepository movements;
    private final LegalMatterRepository matters;
    private final DebtTitleUseCases titles;
    private final ObligationPartyUseCases parties;
    private final CollateralStatusUpdater collaterals;
    private final PrescriptionInterruptions interruptions;
    private final DeadlineRecorder deadlineRecorder;
    private final DeadlineCommands deadlineCommands;
    private final LegalProviders providers;
    private final MatterRequirements requirements;
    private final MatterRecorder matterRecorder;
    private final ForensicCalendars calendars;
    private final LegalAccess access;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final LegalMetrics metrics;
    private final Clock clock;

    LawsuitService(LawsuitRepository lawsuits, CaseMovementRepository movements, LegalMatterRepository matters,
                   DebtTitleUseCases titles, ObligationPartyUseCases parties, CollateralStatusUpdater collaterals,
                   PrescriptionInterruptions interruptions, DeadlineRecorder deadlineRecorder,
                   DeadlineCommands deadlineCommands, LegalProviders providers, MatterRequirements requirements,
                   MatterRecorder matterRecorder, ForensicCalendars calendars, LegalAccess access, AuditTrail auditTrail,
                   BusinessEventLogger businessEvents, LegalMetrics metrics, Clock clock) {
        this.lawsuits = lawsuits;
        this.movements = movements;
        this.matters = matters;
        this.titles = titles;
        this.parties = parties;
        this.collaterals = collaterals;
        this.interruptions = interruptions;
        this.deadlineRecorder = deadlineRecorder;
        this.deadlineCommands = deadlineCommands;
        this.providers = providers;
        this.requirements = requirements;
        this.matterRecorder = matterRecorder;
        this.calendars = calendars;
        this.access = access;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public LawsuitView register(UUID matterId, LawsuitCommand command) {
        LegalMatter m = access.forCommand(matterId);
        m.requireOpen();
        Guard.requireState(m.getStage().acceptsActions(), "lawsuit.stage_invalid",
                "O ajuizamento exige a autorização do credor");
        LegalRoute route = LegalViews.parse(LegalRoute.class, command.route(), "lawsuit.route_invalid", "Via judicial");
        Guard.require(route.isJudicial(), "lawsuit.route_invalid", "Via judicial obrigatória");
        Guard.requireState(requirements.authorizedRoutes(m.getId()).contains(route), "lawsuit.not_authorized",
                "O credor não autorizou esta via judicial");
        CnjNumber cnj = CnjNumber.parse(command.cnjNumber());
        if (lawsuits.existsByCnjNumber(cnj.digits())) {
            throw new ConflictException("lawsuit.duplicate", "Processo já cadastrado na assessoria");
        }
        Set<UUID> debtors = command.debtorIds() == null || command.debtorIds().isEmpty() ? Set.of(m.getDebtorId())
                : Set.copyOf(command.debtorIds());
        requireParties(m, debtors);
        LocalDate today = LegalViews.today(clock);
        Money claim = command.claimAmount() == null ? m.getDebtAmount() : Money.of(command.claimAmount());
        LitigationPole pole = LegalViews.parseOptional(LitigationPole.class, command.creditorPole(), "lawsuit.pole_invalid",
                "Polo do credor");
        Lawsuit lawsuit = lawsuits.save(new Lawsuit(m.getId(), m.getCreditorId(), cnj, route, command.actionClass(), pole,
                debtors, claim, command.filedOn(), command.courtName(), command.courtUnit(), today,
                providers.courtData().code()));
        auditTrail.record(AuditEntry.of("lawsuit.registered", ENTITY, lawsuit.getId())
                .creditor(m.getCreditorId())
                .created("matterId", m.getId())
                .created("cnjNumber", cnj.formatted())
                .created("route", route)
                .created("courtCode", lawsuit.getCourtCode())
                .created("claimAmount", claim)
                .created("filedOn", lawsuit.getFiledOn())
                .created("debtorIds", debtors.stream().sorted().toList().toString()));
        if (m.getStage() == LegalStage.AWAITING_AUTHORIZATION || m.getStage() == LegalStage.EXTRAJUDICIAL) {
            matterRecorder.changeStage(m, LegalStage.FILED, requirements.of(m, null, null),
                    "Processo " + cnj.formatted() + " distribuído", "lawsuit");
        }
        businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_LAWSUIT_FILED, m).amount(claim)
                .reference(ENTITY, lawsuit.getId())
                .attr("matterId", m.getId()).attr("route", route).attr("courtCode", lawsuit.getCourtCode())
                .attr("segment", cnj.segment()).attr("defendants", debtors.size())
                .attr("creditorPole", lawsuit.getCreditorPole()).attr("responsibleType", m.getResponsibleType())
                .attr("lawFirmId", m.getLawFirmId())
                .attr("daysSinceReferral", java.time.Duration.between(m.getReferredAt(), Instant.now(clock)).toDays()));
        metrics.lawsuitFiled(route);
        return LegalViews.lawsuit(lawsuit);
    }

    /** Polo contrário: devedor do caso jurídico ou coobrigados vigentes dos contratos dos títulos. */
    private void requireParties(LegalMatter m, Set<UUID> debtors) {
        Set<UUID> allowed = new HashSet<>();
        allowed.add(m.getDebtorId());
        m.getTitleIds().stream().map(titles::get).map(DebtTitleUseCases.TitleView::originContractId)
                .filter(Objects::nonNull).distinct()
                .forEach(contract -> parties.list(contract).stream().filter(ObligationPartyUseCases.PartyView::active)
                        .forEach(p -> allowed.add(p.debtorId())));
        Guard.require(allowed.containsAll(debtors), "lawsuit.party_invalid",
                "O polo contrário deve ser o devedor ou coobrigado vigente dos contratos do caso jurídico");
    }

    @Override
    @Transactional(readOnly = true)
    public LawsuitView get(UUID lawsuitId) {
        Lawsuit lawsuit = load(lawsuitId);
        access.visible(lawsuit.getMatterId());
        return LegalViews.lawsuit(lawsuit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LawsuitView> list(UUID matterId) {
        LegalMatter m = access.visible(matterId);
        return lawsuits.findByMatterIdOrderByFiledOnAscCreatedAtAsc(m.getId()).stream().map(LegalViews::lawsuit).toList();
    }

    @Override
    public LawsuitView update(UUID lawsuitId, UpdateLawsuitCommand command) {
        Lawsuit lawsuit = load(lawsuitId);
        LegalMatter m = access.forCommand(lawsuit.getMatterId());
        m.requireOpen();
        String name = lawsuit.getCourtName();
        String unit = lawsuit.getCourtUnit();
        String cls = lawsuit.getActionClass();
        Money claim = lawsuit.getClaimAmount();
        if (lawsuit.describe(command.courtName(), command.courtUnit(), command.actionClass(),
                LegalViews.money(command.claimAmount()))) {
            lawsuits.save(lawsuit);
            auditTrail.record(AuditEntry.of("lawsuit.updated", ENTITY, lawsuit.getId()).creditor(m.getCreditorId())
                    .change("courtName", name, lawsuit.getCourtName())
                    .change("courtUnit", unit, lawsuit.getCourtUnit())
                    .change("actionClass", cls, lawsuit.getActionClass())
                    .change("claimAmount", claim, lawsuit.getClaimAmount()));
        }
        return LegalViews.lawsuit(lawsuit);
    }

    @Override
    public LawsuitView changeStatus(UUID lawsuitId, String status, String reason) {
        Lawsuit lawsuit = load(lawsuitId);
        LegalMatter m = access.forCommand(lawsuit.getMatterId());
        LawsuitStatus target = LegalViews.parse(LawsuitStatus.class, status, "lawsuit.status_invalid", "Situação do processo");
        LawsuitStatus previous = lawsuit.changeStatus(target, reason);
        lawsuits.save(lawsuit);
        auditTrail.record(AuditEntry.of("lawsuit.status_changed", ENTITY, lawsuit.getId()).creditor(m.getCreditorId())
                .reason(lawsuit.getStatusReason()).change("status", previous, lawsuit.getStatus()));
        return LegalViews.lawsuit(lawsuit);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MovementView> movements(UUID lawsuitId, PageQuery page) {
        Lawsuit lawsuit = load(lawsuitId);
        access.visible(lawsuit.getMatterId());
        return PageResponse.of(movements.findByLawsuitIdOrderByOccurredOnDescCreatedAtDesc(lawsuit.getId(),
                page.toPageable(Sort.unsorted())), LegalViews::movement);
    }

    @Override
    public MovementResult recordMovement(UUID lawsuitId, MovementCommand command) {
        Lawsuit lawsuit = load(lawsuitId);
        LegalMatter m = access.forCommand(lawsuit.getMatterId());
        m.requireOpen();
        MovementType type = LegalViews.parse(MovementType.class, command.type(), "legal_movement.type_invalid",
                "Natureza do andamento");
        if (command.collateralId() != null) {
            CollateralView collateral = collaterals.get(command.collateralId());
            Guard.require(collateral.creditorId().equals(m.getCreditorId()), "legal_movement.collateral_invalid",
                    "Garantia de outro credor");
        }
        Guard.require(command.collateralStatus() == null || command.collateralId() != null,
                "legal_movement.collateral_required", "Informe a garantia cuja situação muda");
        CaseMovement movement = movements.save(new CaseMovement(lawsuit.getId(), m.getId(), m.getCreditorId(),
                command.occurredOn(), type, command.description(), MovementSource.MANUAL, null, null,
                LegalViews.money(command.amount()), command.collateralId(), LegalViews.today(clock), lawsuit.getFiledOn()));
        int interrupted = applyEffects(m, lawsuit, movement, "movement");
        if (command.collateralStatus() != null) {
            collaterals.change(command.collateralId(), command.collateralStatus().trim().toUpperCase(),
                    "Andamento " + type.name() + " do processo " + lawsuit.cnj().formatted());
        }
        DeadlineView deadlineView = null;
        if (command.deadline() != null) {
            LegalDeadline deadline = deadlineRecorder.create(m, deadlineCommands.spec(m, command.deadline(), lawsuit,
                    movement.getId()), "movement");
            deadlineView = LegalViews.deadline(deadline,
                    calendars.remaining(LegalViews.today(clock), deadline.getDueDate(), deadline.getCourtCode()));
        }
        matters.save(m);
        return new MovementResult(LegalViews.movement(movement), deadlineView, LegalViews.matter(m), interrupted);
    }

    /**
     * Efeitos do andamento (manual ou capturado): citação → interrupção da prescrição e FILED →
     * IN_PROGRESS; levantamento → valor recuperado; registro, auditoria e evento de negócio.
     *
     * @return interrupções de prescrição registradas
     */
    int applyEffects(LegalMatter m, Lawsuit lawsuit, CaseMovement movement, String cause) {
        int interrupted = 0;
        if (movement.getType() == MovementType.CITATION) {
            interrupted = interruptions.register(m.getTitleIds(), "CITATION", movement.getOccurredOn(),
                    "Citação no processo " + lawsuit.cnj().formatted(), movement.getId(), null);
            if (m.getStage() == LegalStage.FILED) {
                matterRecorder.changeStage(m, LegalStage.IN_PROGRESS, requirements.of(m, null, null),
                        "Citação no processo " + lawsuit.cnj().formatted(), cause);
            }
        }
        if (movement.getType() == MovementType.FUNDS_RELEASED) {
            m.addRecovered(movement.getAmount());
            businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_RECOVERY_RECORDED, m)
                    .amount(movement.getAmount()).reference(MOVEMENT, movement.getId())
                    .attr("matterId", m.getId()).attr("route", lawsuit.getRoute())
                    .attr("recoveredTotal", m.getRecoveredAmount().amount()).attr("stage", m.getStage()));
        }
        auditTrail.record(AuditEntry.of("case_movement.recorded", MOVEMENT, movement.getId())
                .creditor(m.getCreditorId())
                .created("lawsuitId", lawsuit.getId())
                .created("type", movement.getType())
                .created("occurredOn", movement.getOccurredOn())
                .created("source", movement.getSource())
                .created("amount", movement.getAmount())
                .meta("prescriptionInterruptions", interrupted));
        businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_MOVEMENT_RECORDED, m)
                .amount(movement.getAmount()).reference(MOVEMENT, movement.getId())
                .attr("matterId", m.getId()).attr("lawsuitId", lawsuit.getId()).attr("type", movement.getType())
                .attr("source", movement.getSource()).attr("provider", movement.getProvider())
                .attr("route", lawsuit.getRoute()).attr("courtCode", lawsuit.getCourtCode())
                .attr("prescriptionInterruptions", interrupted).attr("stage", m.getStage()));
        metrics.movement(movement.getType(), movement.getSource());
        return interrupted;
    }

    @Override
    public SyncResult synchronize(UUID lawsuitId) {
        Lawsuit lawsuit = load(lawsuitId);
        LegalMatter m = access.forCommand(lawsuit.getMatterId());
        m.requireOpen();
        providers.requireAutomatic(providers.courtData(), "court_data", "fetch");
        return capture(m, lawsuit);
    }

    /** Captura de andamentos do processo pelo provedor automático (também usada pela varredura diária). */
    SyncResult capture(LegalMatter m, Lawsuit lawsuit) {
        CourtDataProvider provider = providers.courtData();
        LocalDate since = lawsuit.getLastSyncedAt() == null ? null
                : LocalDate.ofInstant(lawsuit.getLastSyncedAt(), br.com.gestorcobranca.shared.config.TimeConfig.BUSINESS_ZONE)
                .minusDays(7);
        CourtCapture result = provider.fetch(new CourtDataProvider.CourtQuery(lawsuit.getCnjNumber(),
                lawsuit.getCourtCode(), since));
        int captured = 0;
        int duplicates = 0;
        LocalDate today = LegalViews.today(clock);
        if (result.found()) {
            for (CapturedMovement c : result.movements()) {
                if (movements.existsByLawsuitIdAndProviderAndExternalId(lawsuit.getId(), provider.code(), c.externalId())) {
                    duplicates++;
                    continue;
                }
                MovementType type = parseCaptured(c.type());
                CaseMovement movement = movements.save(new CaseMovement(lawsuit.getId(), m.getId(), m.getCreditorId(),
                        c.occurredOn(), type, c.description() == null || c.description().isBlank()
                        ? type.name() : c.description(), MovementSource.PROVIDER, provider.code(), c.externalId(), null,
                        null, today, lawsuit.getFiledOn()));
                applyEffects(m, lawsuit, movement, "capture");
                captured++;
            }
            if (lawsuit.describe(lawsuit.getCourtName() == null ? result.courtName() : lawsuit.getCourtName(),
                    lawsuit.getCourtUnit() == null ? result.courtUnit() : lawsuit.getCourtUnit(),
                    lawsuit.getActionClass() == null ? result.actionClass() : lawsuit.getActionClass(), null)) {
                auditTrail.record(AuditEntry.of("lawsuit.updated", ENTITY, lawsuit.getId()).creditor(m.getCreditorId())
                        .meta("cause", "capture"));
            }
        }
        Instant now = Instant.now(clock);
        lawsuit.markSynced(now);
        lawsuits.save(lawsuit);
        matters.save(m);
        metrics.captured(provider.code(), captured);
        LOG.atInfo().addKeyValue("lawsuitId", lawsuit.getId()).addKeyValue("provider", provider.code())
                .addKeyValue("found", result.found()).addKeyValue("captured", captured)
                .addKeyValue("duplicates", duplicates).log("Captura de andamentos concluída");
        return new SyncResult(lawsuit.getId(), provider.code(), result.found(), captured, duplicates, now);
    }

    private static MovementType parseCaptured(String type) {
        if (type == null) {
            return MovementType.OTHER;
        }
        try {
            return MovementType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return MovementType.OTHER;
        }
    }

    private Lawsuit load(UUID lawsuitId) {
        return lawsuits.findById(lawsuitId).orElseThrow(() -> ResourceNotFoundException.of(ENTITY, lawsuitId));
    }
}
