package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.legal.application.port.in.LegalEligibilityUseCases;
import br.com.gestorcobranca.legal.application.service.CaseFactsReader.TitleSnapshot;
import br.com.gestorcobranca.legal.domain.model.LegalEligibilityRule;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.repository.LegalEligibilityRuleRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalMatterRepository;
import br.com.gestorcobranca.legal.domain.service.EligibilityEvaluator.Outcome;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
class LegalEligibilityService implements LegalEligibilityUseCases {

    private static final String ENTITY = "legal_eligibility_rule";

    private final LegalEligibilityRuleRepository rules;
    private final LegalMatterRepository matters;
    private final CaseUseCases cases;
    private final PortfolioUseCases portfolios;
    private final CaseFactsReader facts;
    private final Referrals referrals;
    private final LegalAccess access;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;

    LegalEligibilityService(LegalEligibilityRuleRepository rules, LegalMatterRepository matters, CaseUseCases cases,
                            PortfolioUseCases portfolios, CaseFactsReader facts, Referrals referrals,
                            LegalAccess access, AccessScope accessScope, AuditTrail auditTrail) {
        this.rules = rules;
        this.matters = matters;
        this.cases = cases;
        this.portfolios = portfolios;
        this.facts = facts;
        this.referrals = referrals;
        this.access = access;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleView> listRules() {
        access.requireInternal(ENTITY);
        return rules.findAllByOrderByCreatedAtAsc().stream()
                .filter(r -> accessScope.canAccessCreditor(r.getCreditorId()))
                .map(LegalViews::rule).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RuleView getRule(UUID ruleId) {
        access.requireInternal(ENTITY);
        return LegalViews.rule(visible(ruleId));
    }

    @Override
    public RuleView createRule(RuleCommand command) {
        access.requireInternal(ENTITY);
        if (command.creditorId() == null) {
            throw new BusinessRuleException("legal_rule.creditor_required", "Credor obrigatório");
        }
        access.requireCreditor(command.creditorId());
        if (command.portfolioId() != null) {
            PortfolioUseCases.PortfolioView portfolio = portfolios.get(command.portfolioId());
            if (!portfolio.creditorId().equals(command.creditorId())) {
                throw new BusinessRuleException("legal_rule.portfolio_mismatch", "A carteira não pertence ao credor");
            }
        }
        LegalEligibilityRule rule = rules.save(new LegalEligibilityRule(command.creditorId(), command.portfolioId(),
                criteria(command)));
        auditTrail.record(AuditEntry.of("legal_eligibility_rule.created", ENTITY, rule.getId())
                .creditor(rule.getCreditorId())
                .created("portfolioId", rule.getPortfolioId())
                .created("name", rule.getName())
                .created("criteria", describe(rule)));
        return LegalViews.rule(rule);
    }

    @Override
    public RuleView updateRule(UUID ruleId, RuleCommand command) {
        access.requireInternal(ENTITY);
        LegalEligibilityRule rule = forCommand(ruleId);
        String before = describe(rule);
        String previousName = rule.getName();
        if (rule.define(criteria(command))) {
            rules.save(rule);
            auditTrail.record(AuditEntry.of("legal_eligibility_rule.updated", ENTITY, rule.getId())
                    .creditor(rule.getCreditorId())
                    .change("name", previousName, rule.getName())
                    .change("criteria", before, describe(rule)));
        }
        return LegalViews.rule(rule);
    }

    @Override
    public RuleView setRuleActive(UUID ruleId, boolean active) {
        access.requireInternal(ENTITY);
        LegalEligibilityRule rule = forCommand(ruleId);
        if (rule.setActive(active)) {
            rules.save(rule);
            auditTrail.record(AuditEntry.of(active ? "legal_eligibility_rule.activated"
                    : "legal_eligibility_rule.deactivated", ENTITY, rule.getId())
                    .creditor(rule.getCreditorId()).change("active", !active, active));
        }
        return LegalViews.rule(rule);
    }

    @Override
    @Transactional(readOnly = true)
    public EligibilityPreview evaluate(UUID collectionCaseId) {
        access.requireInternal(ENTITY);
        CaseView c = cases.get(collectionCaseId);
        List<TitleSnapshot> snapshots = facts.openTitlesOf(c);
        RuleOutcome manual = outcome(null, referrals.evaluate(c, snapshots, null, Referrals.MANUAL_STATUSES));
        List<RuleOutcome> byRule = rules.findByCreditorIdAndActiveTrueOrderByCreatedAtAsc(c.creditorId()).stream()
                .filter(r -> r.appliesTo(c.creditorId(), c.portfolioId()))
                .map(r -> outcome(r, referrals.evaluate(c, snapshots, r, Referrals.AUTOMATIC_STATUSES)))
                .toList();
        return new EligibilityPreview(c.id(), matters.findOpenByCollectionCaseId(c.id()).map(LegalMatter::getId)
                .orElse(null), manual, byRule);
    }

    private static RuleOutcome outcome(LegalEligibilityRule rule, Outcome o) {
        return new RuleOutcome(rule == null ? null : rule.getId(), rule == null ? null : rule.getName(), o.eligible(),
                o.unmet(), o.titleIds(), o.prescribedExcluded(), o.disputedExcluded(), LegalViews.names(o.flags()),
                o.openBalance().amount(), o.maxDaysOverdue());
    }

    private static LegalEligibilityRule.Criteria criteria(RuleCommand c) {
        return new LegalEligibilityRule.Criteria(c.name(), c.minDaysOverdue(), Money.ofNullable(c.minOpenBalance()),
                c.requireCollateral(), c.requireExecutiveTitle(), c.minBrokenAgreements(), c.prescriptionWithinDays());
    }

    private static String describe(LegalEligibilityRule r) {
        return "minDaysOverdue=%s;minOpenBalance=%s;requireCollateral=%s;requireExecutiveTitle=%s;minBrokenAgreements=%s;prescriptionWithinDays=%s"
                .formatted(r.getMinDaysOverdue(), r.getMinOpenBalance() == null ? null : r.getMinOpenBalance().amount(),
                        r.isRequireCollateral(), r.isRequireExecutiveTitle(), r.getMinBrokenAgreements(),
                        r.getPrescriptionWithinDays());
    }

    private LegalEligibilityRule visible(UUID ruleId) {
        LegalEligibilityRule rule = rules.findById(ruleId).orElseThrow(() -> ResourceNotFoundException.of(ENTITY, ruleId));
        accessScope.requireVisible(ENTITY, ruleId, rule.getCreditorId());
        return rule;
    }

    private LegalEligibilityRule forCommand(UUID ruleId) {
        LegalEligibilityRule rule = visible(ruleId);
        access.requireCreditor(rule.getCreditorId());
        return rule;
    }
}
