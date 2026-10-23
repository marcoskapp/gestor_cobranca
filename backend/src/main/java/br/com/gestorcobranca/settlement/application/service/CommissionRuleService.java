package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractView;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases;
import br.com.gestorcobranca.settlement.domain.model.CommissionRuleSet;
import br.com.gestorcobranca.settlement.domain.model.RuleSetStatus;
import br.com.gestorcobranca.settlement.domain.model.SettlementStatement;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules.ReceiptProfile;
import br.com.gestorcobranca.settlement.domain.model.rules.PaymentType;
import br.com.gestorcobranca.settlement.domain.model.rules.ReceivedBy;
import br.com.gestorcobranca.settlement.domain.repository.CommissionRuleSetRepository;
import br.com.gestorcobranca.settlement.domain.repository.ReceiptRepository;
import br.com.gestorcobranca.settlement.domain.repository.SettlementStatementRepository;
import br.com.gestorcobranca.settlement.domain.service.CommissionCalculator;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
class CommissionRuleService implements CommissionRuleUseCases {

    static final String RESOURCE = "commission_rule_set";

    private final CommissionRuleSetRepository ruleSets;
    private final ReceiptRepository receipts;
    private final SettlementStatementRepository statements;
    private final ServiceContractUseCases contracts;
    private final RatingService rating;
    private final RulesCodec codec;
    private final SettlementViews views;
    private final SettlementAccess access;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final SettlementMetrics metrics;
    private final Clock clock;

    CommissionRuleService(CommissionRuleSetRepository ruleSets, ReceiptRepository receipts,
                          SettlementStatementRepository statements, ServiceContractUseCases contracts, RatingService rating,
                          RulesCodec codec, SettlementViews views, SettlementAccess access, AccessScope accessScope,
                          CurrentActor currentActor, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                          SettlementMetrics metrics, Clock clock) {
        this.ruleSets = ruleSets;
        this.receipts = receipts;
        this.statements = statements;
        this.contracts = contracts;
        this.rating = rating;
        this.codec = codec;
        this.views = views;
        this.access = access;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public RuleSetView createDraft(RuleSetCommand command) {
        accessScope.requirePermission(Permission.COMMISSION_MANAGE);
        Guard.require(command.contractId() != null, "commission_rule_set.contract_required", "Contrato obrigatório");
        ContractView contract = contracts.get(command.contractId());
        accessScope.requireCreditor(contract.creditorId());
        if (ruleSets.findByContractIdAndStatus(contract.id(), RuleSetStatus.DRAFT).isPresent()) {
            throw new ConflictException("commission_rule_set.draft_exists",
                    "Já existe rascunho de regras para o contrato: altere-o ou descarte-o");
        }
        String rules = codec.write(command.rules());
        CommissionRuleSet ruleSet = ruleSets.save(new CommissionRuleSet(contract.creditorId(), contract.id(),
                ruleSets.nextVersion(contract.id()), rules, CommissionRules.SCHEMA_VERSION, command.notes(),
                currentActor.actorId(), Instant.now(clock)));
        auditTrail.record(AuditEntry.of("commission_rule_set.drafted", RESOURCE, ruleSet.getId())
                .creditor(ruleSet.getCreditorId()).created("contractId", contract.id())
                .created("ruleVersion", ruleSet.getRuleVersion()).created("rules", rules));
        return views.ruleSet(ruleSet);
    }

    @Override
    public RuleSetView updateDraft(UUID ruleSetId, RuleSetCommand command) {
        accessScope.requirePermission(Permission.COMMISSION_MANAGE);
        CommissionRuleSet ruleSet = loadForCommand(ruleSetId);
        Guard.require(command.contractId() == null || command.contractId().equals(ruleSet.getContractId()),
                "commission_rule_set.contract_mismatch", "O rascunho pertence a outro contrato");
        String before = ruleSet.getRules();
        String rules = codec.write(command.rules());
        ruleSet.updateDraft(rules, CommissionRules.SCHEMA_VERSION, command.notes(), currentActor.actorId(),
                Instant.now(clock));
        ruleSets.save(ruleSet);
        auditTrail.record(AuditEntry.of("commission_rule_set.draft_updated", RESOURCE, ruleSet.getId())
                .creditor(ruleSet.getCreditorId()).change("rules", before, rules));
        return views.ruleSet(ruleSet);
    }

    @Override
    public RuleSetView publish(UUID ruleSetId, LocalDate effectiveFrom) {
        accessScope.requirePermission(Permission.COMMISSION_MANAGE);
        CommissionRuleSet ruleSet = loadForCommand(ruleSetId);
        Guard.require(effectiveFrom != null, "commission_rule_set.effective_from_required", "Informe o início da vigência");
        ContractView contract = contracts.get(ruleSet.getContractId());
        Guard.require(!effectiveFrom.isBefore(contract.startDate())
                        && (contract.endDate() == null || !effectiveFrom.isAfter(contract.endDate())),
                "commission_rule_set.outside_contract", "Vigência fora do período do contrato de serviço");
        Optional<LocalDate> lastRated = receipts.lastRatedCompetence(ruleSet.getContractId());
        if (lastRated.isPresent() && !effectiveFrom.isAfter(lastRated.get())) {
            throw new BusinessRuleException("commission_rule_set.retroactive",
                    "Há recebimentos apurados até " + lastRated.get() + " neste contrato: a nova versão deve começar "
                            + "depois disso (recebimentos apurados não mudam de regra)");
        }
        Optional<LocalDate> lastClosed = statements.findByCreditorIdOrderByPeriodStartDesc(ruleSet.getCreditorId())
                .stream().filter(s -> s.getStatus().isClosed()).map(SettlementStatement::getPeriodEnd).findFirst();
        if (lastClosed.isPresent() && !effectiveFrom.isAfter(lastClosed.get())) {
            throw new BusinessRuleException("commission_rule_set.retroactive",
                    "Período até " + lastClosed.get() + " já prestado contas: a nova versão deve começar depois");
        }
        Optional<CommissionRuleSet> current = ruleSets.findByContractIdOrderByRuleVersionDesc(ruleSet.getContractId())
                .stream().filter(r -> r.getStatus() == RuleSetStatus.PUBLISHED).findFirst();
        if (current.isPresent()) {
            CommissionRuleSet previous = current.get();
            Guard.require(previous.getEffectiveTo() == null, "commission_rule_set.previous_ended",
                    "A versão vigente já tem fim de vigência");
            Guard.require(effectiveFrom.isAfter(previous.getEffectiveFrom()), "commission_rule_set.before_previous",
                    "A nova versão deve começar depois do início da versão vigente (" + previous.getEffectiveFrom() + ")");
            previous.endOn(effectiveFrom.minusDays(1));
            ruleSets.save(previous);
            auditTrail.record(AuditEntry.of("commission_rule_set.ended", RESOURCE, previous.getId())
                    .creditor(previous.getCreditorId()).change("effectiveTo", null, previous.getEffectiveTo()));
        }
        ruleSet.publish(effectiveFrom, currentActor.actorId(), Instant.now(clock));
        ruleSets.save(ruleSet);
        CommissionRules rules = codec.read(ruleSet);
        auditTrail.record(AuditEntry.of("commission_rule_set.published", RESOURCE, ruleSet.getId())
                .creditor(ruleSet.getCreditorId()).change("status", RuleSetStatus.DRAFT, RuleSetStatus.PUBLISHED)
                .created("effectiveFrom", effectiveFrom).created("ruleVersion", ruleSet.getRuleVersion())
                .meta("editedBy", ruleSet.getRulesEditedBy()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.COMMISSION_RULES_PUBLISHED).creditor(ruleSet.getCreditorId())
                .reference(RESOURCE, ruleSet.getId()).attr("contractId", ruleSet.getContractId())
                .attr("ruleVersion", ruleSet.getRuleVersion()).attr("effectiveFrom", effectiveFrom.toString())
                .attr("defaultRate", rules.defaultRate()).attr("specificRules", rules.rateRules().size())
                .attr("fixedFeePerAgreement", rules.fixedFeePerAgreement())
                .attr("honorariumOwnership", rules.honorarium().ownership())
                .attr("honorariumAgencyShare", rules.honorarium().effectiveAgencyShare())
                .attr("reconciliationRequired", rules.reconciliationRequired()));
        metrics.rulesPublished();
        rating.ratePending(ruleSet.getCreditorId());
        return views.ruleSet(ruleSet);
    }

    @Override
    public RuleSetView discard(UUID ruleSetId) {
        accessScope.requirePermission(Permission.COMMISSION_MANAGE);
        CommissionRuleSet ruleSet = loadForCommand(ruleSetId);
        ruleSet.discard();
        ruleSets.save(ruleSet);
        auditTrail.record(AuditEntry.of("commission_rule_set.discarded", RESOURCE, ruleSet.getId())
                .creditor(ruleSet.getCreditorId()).change("status", RuleSetStatus.DRAFT, RuleSetStatus.DISCARDED));
        return views.ruleSet(ruleSet);
    }

    @Override
    @Transactional(readOnly = true)
    public RuleSetView get(UUID ruleSetId) {
        access.requireAny(Permission.COMMISSION_VIEW, Permission.COMMISSION_MANAGE);
        return views.ruleSet(loadVisible(ruleSetId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleSetView> list(UUID creditorId, UUID contractId) {
        access.requireAny(Permission.COMMISSION_VIEW, Permission.COMMISSION_MANAGE);
        List<CommissionRuleSet> found;
        if (contractId != null) {
            ContractView contract = contracts.get(contractId);
            accessScope.requireVisible("service_contract", contractId, contract.creditorId());
            found = ruleSets.findByContractIdOrderByRuleVersionDesc(contractId);
        } else if (creditorId != null) {
            accessScope.requireVisible("creditor", creditorId, creditorId);
            found = ruleSets.findByCreditorIdOrderByRuleVersionDesc(creditorId);
        } else {
            found = ruleSets.findVisible(accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of()));
        }
        return found.stream().map(views::ruleSet).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CalculationView simulate(SimulateCommand c) {
        access.requireAny(Permission.COMMISSION_VIEW, Permission.COMMISSION_MANAGE);
        CommissionRules rules;
        if (c.ruleSetId() != null) {
            rules = codec.read(loadVisible(c.ruleSetId()));
        } else {
            Guard.require(c.rules() != null, "commission_rules.required", "Informe a versão ou as regras");
            rules = c.rules().validated();
        }
        Guard.require(c.amount() != null && c.amount().signum() > 0, "commission.amount_invalid",
                "Valor recebido deve ser positivo");
        PaymentType type = SettlementAccess.parse(PaymentType.class, c.paymentType() == null ? "LUMP_SUM"
                : c.paymentType(), "commission.payment_type_invalid");
        ReceivedBy receivedBy = SettlementAccess.parse(ReceivedBy.class, c.receivedBy() == null ? "AGENCY"
                : c.receivedBy(), "commission.received_by_invalid");
        CommissionCalculator.Rating result = CommissionCalculator.rate(rules, new CommissionCalculator.Input(
                Money.of(c.amount()), Money.ofNullable(c.honorarium()),
                new ReceiptProfile(c.portfolioId(), c.daysPastDue(), type, receivedBy, c.renegotiation()),
                c.firstReceiptOfAgreement()));
        return SettlementViews.calculation(result);
    }

    private CommissionRuleSet loadForCommand(UUID id) {
        CommissionRuleSet ruleSet = loadVisible(id);
        accessScope.requireCreditor(ruleSet.getCreditorId());
        return ruleSet;
    }

    private CommissionRuleSet loadVisible(UUID id) {
        CommissionRuleSet ruleSet = ruleSets.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        accessScope.requireVisible(RESOURCE, id, ruleSet.getCreditorId());
        return ruleSet;
    }
}
