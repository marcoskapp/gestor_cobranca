package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases.CollateralView;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases;
import br.com.gestorcobranca.legal.application.service.CaseFactsReader.TitleSnapshot;
import br.com.gestorcobranca.legal.domain.model.ChecklistItem;
import br.com.gestorcobranca.legal.domain.model.ChecklistStatus;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.LegalRoute;
import br.com.gestorcobranca.legal.domain.model.LegalSettings;
import br.com.gestorcobranca.legal.domain.model.LegalStage;
import br.com.gestorcobranca.legal.domain.model.MatterFlag;
import br.com.gestorcobranca.legal.domain.model.ViabilityAssessment;
import br.com.gestorcobranca.legal.domain.repository.LegalMatterRepository;
import br.com.gestorcobranca.legal.domain.repository.ViabilityAssessmentRepository;
import br.com.gestorcobranca.legal.domain.service.ViabilityCalculator;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Percentage;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class LegalViabilityService implements LegalViabilityUseCases {

    static final String ENTITY = "viability_assessment";
    private static final Percentage DEFAULT_REALIZATION = Percentage.of("70");
    private static final Percentage DEFAULT_PROBABILITY = Percentage.of("50");
    private static final Set<LegalStage> ASSESSABLE = EnumSet.of(LegalStage.UNDER_REVIEW, LegalStage.NOT_VIABLE,
            LegalStage.AWAITING_AUTHORIZATION);

    private final ViabilityAssessmentRepository assessments;
    private final LegalMatterRepository matters;
    private final CaseFactsReader facts;
    private final LegalSettingsProvider settings;
    private final LegalAccess access;
    private final MatterRecorder matterRecorder;
    private final JsonCodec json;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final LegalMetrics metrics;
    private final Clock clock;

    LegalViabilityService(ViabilityAssessmentRepository assessments, LegalMatterRepository matters,
                          CaseFactsReader facts, LegalSettingsProvider settings, LegalAccess access,
                          MatterRecorder matterRecorder, JsonCodec json, AuditTrail auditTrail,
                          BusinessEventLogger businessEvents, LegalMetrics metrics, Clock clock) {
        this.assessments = assessments;
        this.matters = matters;
        this.facts = facts;
        this.settings = settings;
        this.access = access;
        this.matterRecorder = matterRecorder;
        this.json = json;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Fatos lidos do debt e o resultado do cálculo para as premissas informadas. */
    private record Analysis(ViabilityCalculator.Input input, ViabilityCalculator.Result result,
                            List<TitleSnapshot> snapshots, List<CollateralView> collaterals) {
    }

    @Override
    @Transactional(readOnly = true)
    public ViabilityResultView preview(UUID matterId, AssessmentInput input) {
        LegalMatter m = access.visible(matterId);
        Analysis a = analyze(m, input);
        ViabilityCalculator.Result r = a.result();
        return new ViabilityResultView(r.debtAmount().amount(), r.collectibleDebt().amount(), r.collateralValue().amount(),
                r.collateralRecoverable().amount(), r.otherAssetsRecoverable().amount(), r.estimatedRecoverable().amount(),
                r.expectedCosts().amount(), r.netBenefit().amount(), r.costBenefitRatio(), r.economicallyViable(),
                LegalViews.names(r.availableRoutes()), LegalViews.nameList(r.suggestedRoutes()), LegalViews.names(r.flags()),
                a.collaterals().stream().map(k -> new CollateralFactView(k.id(), k.type(), k.status(), k.estimatedValue(),
                        k.lastAppraisalDate())).toList(),
                a.input().titles().stream().map(t -> new PrescriptionFactView(t.titleId(), t.executiveTitle(),
                        t.executionDate(), t.executionPrescribed(), t.collectionDate(), t.collectionPrescribed(),
                        t.ruleValidated())).toList(),
                suggestedChecklist(a));
    }

    @Override
    public AssessmentView record(UUID matterId, AssessmentCommand command) {
        LegalMatter m = access.forCommand(matterId);
        m.requireOpen();
        Guard.requireState(ASSESSABLE.contains(m.getStage()), "legal_viability.stage_invalid",
                "A análise de viabilidade é feita na triagem (fase UNDER_REVIEW)");
        Guard.require(command.input() != null, "legal_viability.input_required", "Informe as premissas da análise");
        Analysis a = analyze(m, command.input());
        List<LegalRoute> routes = command.recommendedRoutes() == null ? List.of() : command.recommendedRoutes().stream()
                .map(r -> LegalViews.parse(LegalRoute.class, r, "legal_viability.route_invalid", "Via")).toList();
        List<ChecklistItem> checklist = command.checklist() == null ? List.of() : command.checklist().stream()
                .map(e -> new ChecklistItem(e.code(), e.label(), LegalViews.parse(ChecklistStatus.class, e.status(),
                        "legal_viability.checklist_status_invalid", "Situação do item"), e.required()))
                .toList();
        ViabilityAssessment assessment = assessments.save(new ViabilityAssessment(m.getId(), m.getCreditorId(), a.input(),
                a.result(), command.viable(), routes, checklist, command.assetSearchNotes(), command.opinion(),
                collateralSnapshot(a.collaterals()), prescriptionSnapshot(a.input().titles())));
        Money previousCosts = m.getExpectedCosts();
        Money previousRecoverable = m.getEstimatedRecoverable();
        LegalRoute previousRoute = m.getPrimaryRoute();
        m.applyViability(assessment.primaryRoute(), a.result().collectibleDebt(), a.result().expectedCosts(),
                a.result().estimatedRecoverable());
        a.result().flags().forEach(m::flag);
        if (command.viable() && !a.result().economicallyViable()) {
            m.flag(MatterFlag.NEGATIVE_COST_BENEFIT);
        }
        matters.save(m);
        auditTrail.record(AuditEntry.of("viability_assessment.recorded", ENTITY, assessment.getId())
                .creditor(m.getCreditorId())
                .created("matterId", m.getId())
                .created("viable", assessment.isViable())
                .created("recommendedRoutes", LegalViews.nameList(assessment.getRecommendedRoutes()).toString())
                .created("estimatedRecoverable", assessment.getEstimatedRecoverable())
                .created("expectedCosts", assessment.getExpectedCosts())
                .created("netBenefit", assessment.getNetBenefit())
                .meta("matterRoute", m.getPrimaryRoute() == previousRoute ? null : String.valueOf(m.getPrimaryRoute()))
                .meta("previousExpectedCosts", previousCosts)
                .meta("previousEstimatedRecoverable", previousRecoverable));
        businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_VIABILITY_ASSESSED, m)
                .amount(assessment.getEstimatedRecoverable())
                .attr("viable", assessment.isViable()).attr("economicallyViable", assessment.isEconomicallyViable())
                .attr("route", assessment.primaryRoute())
                .attr("routes", String.join(",", LegalViews.nameList(assessment.getRecommendedRoutes())))
                .attr("debtAmount", assessment.getDebtAmount().amount())
                .attr("collectibleDebt", assessment.getCollectibleDebt().amount())
                .attr("collateralValue", assessment.getCollateralValue().amount())
                .attr("expectedCosts", assessment.getExpectedCosts().amount())
                .attr("netBenefit", assessment.getNetBenefit().amount())
                .attr("costBenefitRatio", assessment.getCostBenefitRatio())
                .attr("successProbability", assessment.getSuccessProbability().value())
                .attr("flags", String.join(",", LegalViews.names(assessment.getFlags())))
                .attr("checklistMissing", assessment.getChecklist().stream()
                        .filter(i -> i.getStatus() == ChecklistStatus.MISSING).count()));
        metrics.viability(assessment.isViable(), assessment.isEconomicallyViable());
        return LegalViews.assessment(assessment);
    }

    @Override
    @Transactional(readOnly = true)
    public AssessmentView current(UUID matterId) {
        LegalMatter m = access.visible(matterId);
        return assessments.findFirstByMatterIdOrderByCreatedAtDesc(m.getId()).map(LegalViews::assessment).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssessmentView> history(UUID matterId) {
        LegalMatter m = access.visible(matterId);
        return assessments.findByMatterIdOrderByCreatedAtDesc(m.getId()).stream().map(LegalViews::assessment).toList();
    }

    private Analysis analyze(LegalMatter m, AssessmentInput in) {
        LegalSettings.Values values = settings.current();
        List<TitleSnapshot> snapshots = facts.titles(m.getTitleIds());
        List<CollateralView> collaterals = facts.collateralsOf(snapshots);
        ViabilityCalculator.Input input = new ViabilityCalculator.Input(facts.viabilityTitles(snapshots),
                collaterals.stream().map(k -> new ViabilityCalculator.CollateralFact(k.id(), k.type(), k.status(),
                        Money.ofNullable(k.estimatedValue()))).toList(),
                Money.ofNullable(in == null ? null : in.otherAssetsValue()),
                percentage(in == null ? null : in.realizationRate(), DEFAULT_REALIZATION),
                percentage(in == null ? null : in.successProbability(), DEFAULT_PROBABILITY),
                Money.ofNullable(in == null ? null : in.expectedCosts()), LegalViews.today(clock),
                values.prescriptionWarningDays());
        return new Analysis(input, ViabilityCalculator.calculate(input), snapshots, collaterals);
    }

    private static Percentage percentage(BigDecimal value, Percentage fallback) {
        if (value == null) {
            return fallback;
        }
        Guard.require(value.signum() >= 0 && value.compareTo(BigDecimal.valueOf(100)) <= 0,
                "legal_viability.rate_invalid", "Percentuais entre 0 e 100");
        return Percentage.of(value);
    }

    /** Checklist sugerido conforme a natureza do título e as garantias. */
    private static List<ChecklistEntry> suggestedChecklist(Analysis a) {
        List<ChecklistEntry> items = new ArrayList<>();
        items.add(new ChecklistEntry("CONTRACT", "Contrato ou título original (ou cópia autenticada)", "MISSING", true));
        items.add(new ChecklistEntry("DEBT_STATEMENT", "Demonstrativo atualizado do débito", "MISSING", true));
        items.add(new ChecklistEntry("DEFAULT_NOTICE", "Notificação de constituição em mora", "MISSING",
                a.collaterals().stream().anyMatch(k -> k.type().endsWith("FIDUCIARY_ALIENATION"))));
        items.add(new ChecklistEntry("POWER_OF_ATTORNEY", "Procuração do credor", "MISSING", true));
        if (!a.collaterals().isEmpty()) {
            items.add(new ChecklistEntry("COLLATERAL_REGISTRATION", "Registro da garantia (gravame/matrícula)", "MISSING",
                    true));
        }
        if (a.snapshots().stream().anyMatch(s -> s.contract() != null && "CCB".equals(s.contract().instrumentType()))) {
            items.add(new ChecklistEntry("CCB_STATEMENT", "Planilha de evolução da CCB (Lei 10.931/2004, art. 28)",
                    "MISSING", true));
        }
        return items;
    }

    private String collateralSnapshot(List<CollateralView> collaterals) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CollateralView k : collaterals) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("collateralId", k.id());
            row.put("type", k.type());
            row.put("status", k.status());
            row.put("estimatedValue", k.estimatedValue());
            row.put("lastAppraisalDate", k.lastAppraisalDate() == null ? null : k.lastAppraisalDate().toString());
            rows.add(row);
        }
        return json.toJson(rows);
    }

    private String prescriptionSnapshot(List<ViabilityCalculator.TitleFact> titles) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ViabilityCalculator.TitleFact t : titles) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("titleId", t.titleId());
            row.put("openBalance", t.openBalance().amount());
            row.put("executiveTitle", t.executiveTitle());
            row.put("executionEstimatedDate", t.executionDate() == null ? null : t.executionDate().toString());
            row.put("executionPrescribed", t.executionPrescribed());
            row.put("collectionEstimatedDate", t.collectionDate() == null ? null : t.collectionDate().toString());
            row.put("collectionPrescribed", t.collectionPrescribed());
            row.put("ruleValidated", t.ruleValidated());
            rows.add(row);
        }
        return json.toJson(rows);
    }
}
