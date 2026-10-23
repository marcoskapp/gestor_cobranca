package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.PrescriptionRuleUseCases;
import br.com.gestorcobranca.debt.domain.model.InstrumentType;
import br.com.gestorcobranca.debt.domain.model.InterruptionPolicy;
import br.com.gestorcobranca.debt.domain.model.PrescriptionBase;
import br.com.gestorcobranca.debt.domain.model.PrescriptionClaim;
import br.com.gestorcobranca.debt.domain.model.PrescriptionRule;
import br.com.gestorcobranca.debt.domain.repository.PrescriptionRuleRepository;
import br.com.gestorcobranca.debt.domain.repository.PrescriptionRuleTemplateRepository;
import br.com.gestorcobranca.debt.domain.repository.PrescriptionRuleTemplateRepository.Template;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
class PrescriptionRuleService implements PrescriptionRuleUseCases {

    static final String ENTITY = "prescription_rule";
    static final String SOURCE_MARKET_TEMPLATE = "MARKET_TEMPLATE";

    private final PrescriptionRuleRepository rules;
    private final PrescriptionRuleTemplateRepository templates;
    private final PrescriptionRecalculationJob recalculation;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final Clock clock;

    PrescriptionRuleService(PrescriptionRuleRepository rules, PrescriptionRuleTemplateRepository templates,
                            PrescriptionRecalculationJob recalculation, AccessScope accessScope,
                            CurrentActor currentActor, AuditTrail auditTrail, Clock clock) {
        this.rules = rules;
        this.templates = templates;
        this.recalculation = recalculation;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleView> list() {
        return rules.findAllByOrderByInstrumentTypeAscClaimAsc().stream().map(PrescriptionRuleService::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RuleView get(UUID ruleId) {
        return view(load(ruleId));
    }

    @Override
    public RuleView create(RuleCommand command) {
        accessScope.requireAllCreditors(ENTITY);
        InstrumentType instrumentType = DebtViews.parseEnum(InstrumentType.class, command.instrumentType(),
                "prescription_rule.instrument_type_invalid", "Natureza do título");
        PrescriptionClaim claim = DebtViews.parseEnum(PrescriptionClaim.class, command.claim(),
                "prescription_rule.claim_invalid", "Pretensão");
        if (rules.findByInstrumentTypeAndClaim(instrumentType, claim).isPresent()) {
            throw new ConflictException("prescription_rule.duplicate", "Já existe regra para esta natureza e pretensão");
        }
        PrescriptionRule rule = rules.save(PrescriptionRule.validated(instrumentType, claim, terms(command),
                currentActor.actorId(), Instant.now(clock)));
        auditCreated(rule, "MANUAL");
        recalculation.schedule(instrumentType);
        return view(rule);
    }

    @Override
    public RuleView update(UUID ruleId, RuleCommand command) {
        accessScope.requireAllCreditors(ENTITY);
        PrescriptionRule rule = load(ruleId);
        PrescriptionRule.Terms previous = rule.terms();
        String previousStatus = rule.getReviewStatus().name();
        PrescriptionRule.Terms requested = terms(command);
        boolean recalculate = rule.affectsCalculation(requested);
        boolean changed = rule.update(requested, currentActor.actorId(), Instant.now(clock));
        if (!changed && previousStatus.equals(rule.getReviewStatus().name())) {
            return view(rule);
        }
        rules.save(rule);
        PrescriptionRule.Terms current = rule.terms();
        auditTrail.record(AuditEntry.of("prescription_rule.updated", ENTITY, rule.getId())
                .change("termMonths", previous.termMonths(), current.termMonths())
                .change("base", previous.base(), current.base())
                .change("startOffsetDays", previous.startOffsetDays(), current.startOffsetDays())
                .change("interruptionPolicy", previous.interruptionPolicy(), current.interruptionPolicy())
                .change("alertDays", previous.alertDays(), current.alertDays())
                .change("legalBasis", previous.legalBasis(), current.legalBasis())
                .change("reviewStatus", previousStatus, rule.getReviewStatus())
                .meta("instrumentType", rule.getInstrumentType())
                .meta("claim", rule.getClaim()));
        if (recalculate) {
            recalculation.schedule(rule.getInstrumentType());
        }
        return view(rule);
    }

    @Override
    public RuleView validate(UUID ruleId) {
        accessScope.requireAllCreditors(ENTITY);
        PrescriptionRule rule = load(ruleId);
        if (rule.validate(currentActor.actorId(), Instant.now(clock))) {
            rules.save(rule);
            auditTrail.record(AuditEntry.of("prescription_rule.validated", ENTITY, rule.getId())
                    .change("reviewStatus", "PENDING_LEGAL_REVIEW", rule.getReviewStatus())
                    .meta("instrumentType", rule.getInstrumentType())
                    .meta("claim", rule.getClaim())
                    .meta("termMonths", rule.getTermMonths()));
        }
        return view(rule);
    }

    @Override
    public void delete(UUID ruleId, String reason) {
        accessScope.requireAllCreditors(ENTITY);
        Guard.require(reason != null && !reason.isBlank(), "prescription_rule.reason_required", "Informe o motivo");
        Guard.require(reason.length() <= 500, "prescription_rule.reason_too_long", "Motivo muito longo");
        PrescriptionRule rule = load(ruleId);
        // as prescrições calculadas pela regra são removidas em cascata (sem regra, sem data estimada)
        rules.delete(rule);
        auditTrail.record(AuditEntry.of("prescription_rule.deleted", ENTITY, rule.getId())
                .reason(reason.trim())
                .change("instrumentType", rule.getInstrumentType(), null)
                .change("claim", rule.getClaim(), null)
                .change("termMonths", rule.getTermMonths(), null));
    }

    @Override
    public List<RuleView> applyMarketDefaults() {
        accessScope.requireAllCreditors(ENTITY);
        List<RuleView> created = new ArrayList<>();
        for (Template template : templates.findAll()) {
            if (rules.findByInstrumentTypeAndClaim(template.instrumentType(), template.claim()).isPresent()) {
                continue;
            }
            PrescriptionRule rule = rules.save(PrescriptionRule.fromMarketTemplate(template.instrumentType(),
                    template.claim(), template.terms()));
            auditCreated(rule, SOURCE_MARKET_TEMPLATE);
            recalculation.schedule(rule.getInstrumentType());
            created.add(view(rule));
        }
        return created;
    }

    private void auditCreated(PrescriptionRule rule, String source) {
        auditTrail.record(AuditEntry.of("prescription_rule.created", ENTITY, rule.getId())
                .created("instrumentType", rule.getInstrumentType())
                .created("claim", rule.getClaim())
                .created("termMonths", rule.getTermMonths())
                .created("base", rule.getBase())
                .created("startOffsetDays", rule.getStartOffsetDays())
                .created("interruptionPolicy", rule.getInterruptionPolicy())
                .created("alertDays", rule.getAlertDays())
                .created("legalBasis", rule.getLegalBasis())
                .created("reviewStatus", rule.getReviewStatus())
                .meta("source", source));
    }

    private PrescriptionRule load(UUID ruleId) {
        return rules.findById(ruleId).orElseThrow(() -> ResourceNotFoundException.of(ENTITY, ruleId));
    }

    private static PrescriptionRule.Terms terms(RuleCommand command) {
        PrescriptionBase base = DebtViews.parseEnum(PrescriptionBase.class, command.base(), "prescription_rule.base_invalid",
                "Data-base");
        InterruptionPolicy policy = DebtViews.parseEnum(InterruptionPolicy.class, command.interruptionPolicy(),
                "prescription_rule.interruption_policy_invalid", "Efeito das interrupções");
        return new PrescriptionRule.Terms(command.termMonths(), base, command.startOffsetDays(), policy,
                command.alertDays(), command.legalBasis());
    }

    static RuleView view(PrescriptionRule r) {
        return new RuleView(r.getId(), r.getInstrumentType().name(), r.getClaim().name(), r.getTermMonths(),
                r.getBase().name(), r.getStartOffsetDays(), r.getInterruptionPolicy().name(), r.getAlertDays(),
                r.getLegalBasis(), r.getReviewStatus().name(), r.getReviewedAt(), r.getReviewedBy(), r.getUpdatedAt(),
                r.getUpdatedBy());
    }
}
