package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases;
import br.com.gestorcobranca.legal.domain.model.ClosureReason;
import br.com.gestorcobranca.legal.domain.model.ConductModel;
import br.com.gestorcobranca.legal.domain.model.DeadlineStatus;
import br.com.gestorcobranca.legal.domain.model.LawFirm;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.LegalStage;
import br.com.gestorcobranca.legal.domain.model.ResponsibleType;
import br.com.gestorcobranca.legal.domain.model.StageRequirements;
import br.com.gestorcobranca.legal.domain.model.ViabilityAssessment;
import br.com.gestorcobranca.legal.domain.repository.AgreementRecordRepository;
import br.com.gestorcobranca.legal.domain.repository.CreditorAuthorizationRepository;
import br.com.gestorcobranca.legal.domain.repository.DeadlineSearchCriteria;
import br.com.gestorcobranca.legal.domain.repository.ExtrajudicialMeasureRepository;
import br.com.gestorcobranca.legal.domain.repository.LawFirmMemberRepository;
import br.com.gestorcobranca.legal.domain.repository.LawFirmRepository;
import br.com.gestorcobranca.legal.domain.repository.LawsuitRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalDeadlineRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalDocumentRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalExpenseRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalMatterRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalMatterRepository.StageSummary;
import br.com.gestorcobranca.legal.domain.repository.MatterSearchCriteria;
import br.com.gestorcobranca.legal.domain.repository.StageHistoryRepository;
import br.com.gestorcobranca.legal.domain.repository.ViabilityAssessmentRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class LegalMatterService implements LegalMatterUseCases {

    private final LegalMatterRepository matters;
    private final StageHistoryRepository history;
    private final ViabilityAssessmentRepository assessments;
    private final CreditorAuthorizationRepository authorizations;
    private final ExtrajudicialMeasureRepository measures;
    private final LawsuitRepository lawsuits;
    private final LegalDeadlineRepository deadlines;
    private final LegalExpenseRepository expenses;
    private final LegalDocumentRepository documents;
    private final AgreementRecordRepository agreements;
    private final LawFirmRepository firms;
    private final LawFirmMemberRepository members;
    private final CaseUseCases cases;
    private final DebtorUseCases debtors;
    private final DebtTitleUseCases titles;
    private final UserDirectory users;
    private final Referrals referrals;
    private final MatterRecorder recorder;
    private final MatterRequirements requirements;
    private final AuthorizationRecorder authorizationRecorder;
    private final AgreementLinks agreementLinks;
    private final ForensicCalendars calendars;
    private final LegalSettingsProvider settings;
    private final LegalAccess access;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final Clock clock;

    LegalMatterService(LegalMatterRepository matters, StageHistoryRepository history,
                       ViabilityAssessmentRepository assessments, CreditorAuthorizationRepository authorizations,
                       ExtrajudicialMeasureRepository measures, LawsuitRepository lawsuits,
                       LegalDeadlineRepository deadlines, LegalExpenseRepository expenses,
                       LegalDocumentRepository documents, AgreementRecordRepository agreements, LawFirmRepository firms,
                       LawFirmMemberRepository members, CaseUseCases cases, DebtorUseCases debtors,
                       DebtTitleUseCases titles, UserDirectory users, Referrals referrals, MatterRecorder recorder,
                       MatterRequirements requirements, AuthorizationRecorder authorizationRecorder,
                       AgreementLinks agreementLinks, ForensicCalendars calendars, LegalSettingsProvider settings,
                       LegalAccess access, AccessScope accessScope, AuditTrail auditTrail,
                       BusinessEventLogger businessEvents, Clock clock) {
        this.matters = matters;
        this.history = history;
        this.assessments = assessments;
        this.authorizations = authorizations;
        this.measures = measures;
        this.lawsuits = lawsuits;
        this.deadlines = deadlines;
        this.expenses = expenses;
        this.documents = documents;
        this.agreements = agreements;
        this.firms = firms;
        this.members = members;
        this.cases = cases;
        this.debtors = debtors;
        this.titles = titles;
        this.users = users;
        this.referrals = referrals;
        this.recorder = recorder;
        this.requirements = requirements;
        this.authorizationRecorder = authorizationRecorder;
        this.agreementLinks = agreementLinks;
        this.calendars = calendars;
        this.settings = settings;
        this.access = access;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.clock = clock;
    }

    @Override
    public MatterView refer(ReferCommand command) {
        Guard.require(command.collectionCaseId() != null, "legal_matter.case_required", "Caso de cobrança obrigatório");
        access.requireInternal(LegalAccess.MATTER);
        CaseUseCases.CaseView c = cases.get(command.collectionCaseId());
        access.requireCreditor(c.creditorId());
        return LegalViews.matter(referrals.referManually(c, command.titleIds(), command.reason()));
    }

    @Override
    @Transactional(readOnly = true)
    public MatterView get(UUID matterId) {
        return LegalViews.matter(access.visible(matterId));
    }

    @Override
    @Transactional(readOnly = true)
    public MatterSheet sheet(UUID matterId) {
        LegalMatter m = access.visible(matterId);
        DebtorUseCases.DebtorView debtor = debtors.get(m.getDebtorId());
        List<TitleInfo> titleInfos = m.getTitleIds().stream().sorted().map(titles::get)
                .map(t -> new TitleInfo(t.id(), t.externalId(), t.installmentNumber(), t.dueDate(), t.openBalance(),
                        t.status(), t.daysOverdue()))
                .sorted(java.util.Comparator.comparing(TitleInfo::dueDate)).toList();
        LocalDate today = LegalViews.today(clock);
        return new MatterSheet(LegalViews.matter(m),
                new DebtorInfo(debtor.id(), debtor.name(), debtor.maskedDocument(), debtor.personType()), titleInfos,
                assessments.findFirstByMatterIdOrderByCreatedAtDesc(m.getId()).map(LegalViews::assessment).orElse(null),
                authorizations.findByMatterIdOrderByCreatedAtDesc(m.getId()).stream().map(LegalViews::authorization).toList(),
                measures.findByMatterIdOrderByCreatedAtAsc(m.getId()).stream().map(LegalViews::measure).toList(),
                lawsuits.findByMatterIdOrderByFiledOnAscCreatedAtAsc(m.getId()).stream().map(LegalViews::lawsuit).toList(),
                deadlines.findByMatterIdOrderByDueDateAscCreatedAtAsc(m.getId()).stream()
                        .map(d -> LegalViews.deadline(d, d.getStatus() == DeadlineStatus.PENDING
                                ? calendars.remaining(today, d.getDueDate(), d.getCourtCode()) : null)).toList(),
                expenses.findByMatterIdOrderByIncurredOnDescCreatedAtDesc(m.getId()).stream().map(LegalViews::expense)
                        .toList(),
                documents.findByMatterIdOrderByCreatedAtDesc(m.getId()).stream().map(LegalViews::document).toList(),
                history(m));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MatterView> search(MatterFilter f, PageQuery page) {
        if (f.creditorId() != null) {
            accessScope.requireVisible("creditor", f.creditorId(), f.creditorId());
        }
        MatterSearchCriteria criteria = new MatterSearchCriteria(access.creditorFilter(), access.firmFilter().orElse(null),
                f.creditorId(), f.portfolioId(), f.debtorId(), f.collectionCaseId(),
                LegalViews.parseSet(LegalStage.class, f.stages(), "legal_matter.stage_invalid", "Fase"),
                LegalViews.parseOptional(ResponsibleType.class, f.responsibleType(), "legal_matter.responsible_invalid",
                        "Responsável"),
                f.responsibleUserId(), f.lawFirmId(), f.open());
        return PageResponse.of(matters.search(criteria, page.toPageable(Sort.by(Sort.Direction.DESC, "referredAt")
                .and(Sort.by("id")))), LegalViews::matter);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StageEntryView> history(UUID matterId) {
        return history(access.visible(matterId));
    }

    private List<StageEntryView> history(LegalMatter m) {
        return history.findByMatterIdOrderByOccurredAtAscCreatedAtAsc(m.getId()).stream().map(LegalViews::stage).toList();
    }

    @Override
    public MatterView transition(UUID matterId, TransitionCommand command) {
        LegalMatter m = access.forCommand(matterId);
        LegalStage target = LegalViews.parse(LegalStage.class, command.stage(), "legal_matter.stage_invalid", "Fase");
        ClosureReason closure = LegalViews.parseOptional(ClosureReason.class, command.closureReason(),
                "legal_matter.closure_reason_invalid", "Motivo do encerramento");
        if (m.getStage() == LegalStage.ELIGIBLE && target == LegalStage.UNDER_REVIEW) {
            accessScope.requirePermission(Permission.LEGAL_AUTHORIZE);
        }
        StageRequirements req = requirements.of(m, command.agreementId(), closure);
        recorder.changeStage(m, target, req, command.reason(), "manual");
        if (target == LegalStage.AWAITING_AUTHORIZATION) {
            ViabilityAssessment viability = assessments.findFirstByMatterIdOrderByCreatedAtDesc(m.getId()).orElseThrow();
            if (authorizations.findFirstByMatterIdAndStatus(m.getId(),
                    br.com.gestorcobranca.legal.domain.model.AuthorizationStatus.REQUESTED).isEmpty()) {
                authorizationRecorder.open(m, new LinkedHashSet<>(viability.getRecommendedRoutes()),
                        viability.getExpectedCosts(), "Solicitação a partir da análise de viabilidade", "transition");
            }
        }
        if (target == LegalStage.SETTLED_IN_COURT) {
            agreementLinks.link(m, agreementLinks.recordOf(command.agreementId(), m), true, "manual");
        }
        return LegalViews.matter(m);
    }

    @Override
    public MatterView assign(UUID matterId, AssignCommand command) {
        access.requireInternal(LegalAccess.MATTER);
        LegalMatter m = access.forCommand(matterId);
        ResponsibleType type = LegalViews.parse(ResponsibleType.class, command.responsibleType(),
                "legal_matter.responsible_invalid", "Tipo de responsável");
        ConductModel conduct = settings.current().conductModel();
        ResponsibleType previousType = m.getResponsibleType();
        UUID previousUser = m.getResponsibleUserId();
        UUID previousFirm = m.getLawFirmId();
        boolean changed;
        switch (type) {
            case INTERNAL -> {
                Guard.requireState(conduct.allowsInternal(), "legal_matter.internal_disabled",
                        "A assessoria definiu que os casos jurídicos são conduzidos por escritórios parceiros");
                UserSummary user = requireUser(command.userId(), m);
                Guard.require(members.findByUserIdAndActiveTrue(user.id()).isEmpty(), "legal_matter.responsible_external",
                        "Usuário de escritório parceiro: atribua ao escritório");
                changed = m.assignInternal(user.id());
            }
            case LAW_FIRM -> {
                Guard.requireState(conduct.allowsLawFirms(), "legal_matter.law_firms_disabled",
                        "Condução por escritórios parceiros não habilitada nos parâmetros do jurídico");
                Guard.require(command.lawFirmId() != null, "legal_matter.law_firm_required", "Escritório obrigatório");
                LawFirm firm = firms.findById(command.lawFirmId())
                        .orElseThrow(() -> ResourceNotFoundException.of("law_firm", command.lawFirmId()));
                Guard.requireState(firm.isActive(), "law_firm.inactive", "Escritório inativo");
                if (command.userId() != null) {
                    requireUser(command.userId(), m);
                    Guard.require(members.findByLawFirmIdAndUserIdAndActiveTrue(firm.getId(), command.userId()).isPresent(),
                            "legal_matter.member_required", "O advogado não é membro ativo do escritório");
                }
                changed = m.assignLawFirm(firm.getId(), command.userId());
            }
            default -> throw new BusinessRuleException("legal_matter.responsible_invalid", "Tipo de responsável inválido");
        }
        if (changed) {
            matters.save(m);
            auditTrail.record(AuditEntry.of("legal_matter.assigned", MatterRecorder.ENTITY, m.getId())
                    .creditor(m.getCreditorId())
                    .change("responsibleType", previousType, m.getResponsibleType())
                    .change("responsibleUserId", previousUser, m.getResponsibleUserId())
                    .change("lawFirmId", previousFirm, m.getLawFirmId()));
            businessEvents.log(recorder.base(BusinessEventTypes.LEGAL_MATTER_ASSIGNED, m).amount(m.getDebtAmount())
                    .attr("responsibleType", m.getResponsibleType()).attr("previousType", previousType)
                    .attr("responsibleUserId", m.getResponsibleUserId()).attr("lawFirmId", m.getLawFirmId())
                    .attr("stage", m.getStage()));
        }
        return LegalViews.matter(m);
    }

    private UserSummary requireUser(UUID userId, LegalMatter m) {
        Guard.require(userId != null, "legal_matter.responsible_required", "Responsável obrigatório");
        UserSummary user = users.find(userId).filter(UserSummary::active)
                .orElseThrow(() -> new BusinessRuleException("legal_matter.responsible_invalid",
                        "Responsável inexistente ou inativo"));
        Guard.require(user.canAccessCreditor(m.getCreditorId()), "legal_matter.responsible_out_of_scope",
                "O responsável não tem acesso ao credor do caso");
        Guard.require(user.permissions().contains(Permission.LEGAL_MANAGE.name()), "legal_matter.responsible_permission",
                "O responsável precisa da permissão LEGAL_MANAGE");
        return user;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgreementLinkView> agreements(UUID matterId) {
        LegalMatter m = access.visible(matterId);
        return agreements.findByMatterIdOrderByLastEventAtDesc(m.getId()).stream()
                .map(a -> new AgreementLinkView(a.getAgreementId(), a.getStatus(), a.isJudicial(), a.isBroken(),
                        a.getReplacedAgreementId(), a.getLastEventAt())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Dashboard dashboard(UUID creditorId) {
        if (creditorId != null) {
            accessScope.requireVisible("creditor", creditorId, creditorId);
        }
        UUID firm = access.firmFilter().orElse(null);
        MatterSearchCriteria criteria = new MatterSearchCriteria(access.creditorFilter(), firm, creditorId, null, null,
                null, null, null, null, null, null);
        List<StageSummary> summary = matters.summarize(criteria);
        long open = 0;
        BigDecimal openDebt = BigDecimal.ZERO;
        BigDecimal expensesTotal = BigDecimal.ZERO;
        BigDecimal recovered = BigDecimal.ZERO;
        for (StageSummary s : summary) {
            if (!s.stage().isTerminal()) {
                open += s.matters();
                openDebt = openDebt.add(s.debtAmount());
            }
            expensesTotal = expensesTotal.add(s.expensesTotal());
            recovered = recovered.add(s.recoveredAmount());
        }
        LocalDate today = LegalViews.today(clock);
        Set<UUID> scope = creditorId == null ? access.creditorFilter() : Set.of(creditorId);
        long dueSoon = deadlines.search(new DeadlineSearchCriteria(scope, firm, null, EnumSet.of(DeadlineStatus.PENDING),
                null, today.plusDays(5), null), PageRequest.of(0, 1)).getTotalElements();
        long missed = deadlines.search(new DeadlineSearchCriteria(scope, firm, null, EnumSet.of(DeadlineStatus.MISSED),
                null, null, null), PageRequest.of(0, 1)).getTotalElements();
        return new Dashboard(summary.stream().map(s -> new StageCount(s.stage().name(), s.matters(), s.debtAmount(),
                s.expensesTotal(), s.recoveredAmount())).toList(), open, Money.of(openDebt).amount(),
                Money.of(expensesTotal).amount(), Money.of(recovered).amount(), dueSoon, missed);
    }
}
