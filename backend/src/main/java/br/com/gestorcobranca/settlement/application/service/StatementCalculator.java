package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.out.SettlementSources;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources.OpenDivergence;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources.PaymentFacts;
import br.com.gestorcobranca.settlement.domain.model.AgreementFact;
import br.com.gestorcobranca.settlement.domain.model.CommissionEntry;
import br.com.gestorcobranca.settlement.domain.model.CommissionRuleSet;
import br.com.gestorcobranca.settlement.domain.model.RatingStatus;
import br.com.gestorcobranca.settlement.domain.model.Receipt;
import br.com.gestorcobranca.settlement.domain.model.ReimbursableExpense;
import br.com.gestorcobranca.settlement.domain.model.SettlementStatement;
import br.com.gestorcobranca.settlement.domain.model.SettlementStatement.Totals;
import br.com.gestorcobranca.settlement.domain.model.StatementAdjustment;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules.ReceiptProfile;
import br.com.gestorcobranca.settlement.domain.model.rules.PaymentType;
import br.com.gestorcobranca.settlement.domain.model.rules.ReceivedBy;
import br.com.gestorcobranca.settlement.domain.repository.CommissionEntryRepository;
import br.com.gestorcobranca.settlement.domain.repository.CommissionRuleSetRepository;
import br.com.gestorcobranca.settlement.domain.repository.ReceiptRepository;
import br.com.gestorcobranca.settlement.domain.repository.SettlementFactRepository;
import br.com.gestorcobranca.settlement.domain.repository.StatementRepository;
import br.com.gestorcobranca.settlement.domain.service.CommissionCalculator;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cálculo do demonstrativo (F17/F22, RB08) — executado com o demonstrativo e o credor bloqueados:
 * <ol>
 *   <li>libera o que estava vinculado a este demonstrativo (recálculo);</li>
 *   <li>candidatos: recebimentos apurados e despesas ainda não prestados com competência até o fim do
 *       período (inclusive de períodos anteriores que chegaram depois do fechamento deles);</li>
 *   <li>recebimento na conta da assessoria com regra que exige conciliação entra só se o pagamento estiver
 *       conciliado (ou estornado por inteiro dentro do período); o restante fica pendente e é destacado;</li>
 *   <li>compensação de estorno entra com o original (ou se o original já foi prestado);</li>
 *   <li>totais = soma dos lançamentos do razão + despesas + ajustes aplicados; informativos: valor negociado,
 *       comissão prevista (acordos em aberto), pendências e divergências de conciliação abertas;</li>
 *   <li>vincula os itens incluídos e grava a memória (versões de regras, pendências, divergências).</li>
 * </ol>
 */
@Component
class StatementCalculator {

    private final ReceiptRepository receipts;
    private final CommissionEntryRepository entries;
    private final CommissionRuleSetRepository ruleSets;
    private final SettlementFactRepository facts;
    private final StatementRepository statements;
    private final SettlementSources sources;
    private final RulesCodec codec;
    private final JsonCodec json;

    StatementCalculator(ReceiptRepository receipts, CommissionEntryRepository entries,
                        CommissionRuleSetRepository ruleSets, SettlementFactRepository facts,
                        StatementRepository statements, SettlementSources sources, RulesCodec codec, JsonCodec json) {
        this.receipts = receipts;
        this.entries = entries;
        this.ruleSets = ruleSets;
        this.facts = facts;
        this.statements = statements;
        this.sources = sources;
        this.codec = codec;
        this.json = json;
    }

    record Result(Totals totals, String calculationJson, int included) {
    }

    Result calculate(SettlementStatement s, Instant now, LocalDate today) {
        release(s);
        LocalDate end = s.getPeriodEnd();
        List<Receipt> candidates = receipts.findByCreditorIdAndStatementIdIsNullAndCompetenceDateLessThanEqual(
                s.getCreditorId(), end);
        Map<UUID, CommissionEntry> entryByReceipt = entries.findByReceiptIdIn(candidates.stream().map(Receipt::getId)
                .toList()).stream().collect(Collectors.toMap(CommissionEntry::getReceiptId, Function.identity()));
        Map<UUID, CommissionRules> rulesById = new HashMap<>();
        Map<UUID, Optional<PaymentFacts>> paymentByAllocation = new HashMap<>();

        Map<UUID, Money> reversedInPeriod = new HashMap<>();
        for (Receipt r : candidates) {
            if (!r.isPayment() && r.getRatingStatus() == RatingStatus.RATED) {
                reversedInPeriod.merge(r.getReversesReceiptId(), r.getAmount().negate(), Money::plus);
            }
        }
        List<Receipt> included = new ArrayList<>();
        Set<UUID> includedIds = new HashSet<>();
        List<Map<String, Object>> pendingReconciliation = new ArrayList<>();
        List<Map<String, Object>> pendingRules = new ArrayList<>();
        Money pendingReconciliationAmount = Money.ZERO;
        Money pendingRulesAmount = Money.ZERO;
        Map<UUID, Map<String, Object>> versions = new LinkedHashMap<>();
        Map<UUID, Boolean> reconciledByReceipt = new HashMap<>();

        for (Receipt r : candidates.stream().filter(Receipt::isPayment).toList()) {
            if (r.getRatingStatus() != RatingStatus.RATED) {
                pendingRulesAmount = pendingRulesAmount.plus(r.getAmount());
                pendingRules.add(item(r, Map.of("reason", r.getPendingReason() == null ? "NOT_RATED"
                        : r.getPendingReason())));
                continue;
            }
            CommissionEntry entry = entryByReceipt.get(r.getId());
            CommissionRules rules = rulesById.computeIfAbsent(entry.getRuleSetId(), this::rules);
            boolean reconciled = r.getReceivedBy() == ReceivedBy.CREDITOR || paymentByAllocation
                    .computeIfAbsent(r.getAllocationId(), sources::paymentOfAllocation)
                    .map(PaymentFacts::reconciled).orElse(false);
            boolean fullyReversed = reversedInPeriod.getOrDefault(r.getId(), Money.ZERO).equals(r.getAmount());
            if (rules.reconciliationRequired() && !reconciled && !fullyReversed) {
                pendingReconciliationAmount = pendingReconciliationAmount.plus(r.getAmount());
                pendingReconciliation.add(item(r, Map.of("paymentId", String.valueOf(r.getPaymentId()))));
                continue;
            }
            reconciledByReceipt.put(r.getId(), reconciled);
            included.add(r);
            includedIds.add(r.getId());
            versions.putIfAbsent(entry.getRuleSetId(), Map.of("ruleSetId", entry.getRuleSetId(),
                    "ruleVersion", entry.getRuleVersion(), "contractId", String.valueOf(r.getContractId())));
        }
        for (Receipt r : candidates.stream().filter(c -> !c.isPayment()).toList()) {
            if (r.getRatingStatus() != RatingStatus.RATED) {
                pendingRules.add(item(r, Map.of("reason", "ORIGINAL_PENDING")));
                continue;
            }
            boolean originalIncluded = includedIds.contains(r.getReversesReceiptId())
                    || receipts.findById(r.getReversesReceiptId()).map(o -> o.getStatementId() != null).orElse(false);
            if (originalIncluded) {
                included.add(r);
                includedIds.add(r.getId());
            }
        }

        Accumulator totals = new Accumulator();
        int priorPeriod = 0;
        for (Receipt r : included) {
            CommissionEntry e = entryByReceipt.get(r.getId());
            if (r.isPayment()) {
                if (r.getReceivedBy() == ReceivedBy.AGENCY) {
                    totals.receivedAgency = totals.receivedAgency.plus(r.getAmount());
                } else {
                    totals.receivedCreditor = totals.receivedCreditor.plus(r.getAmount());
                }
                if (reconciledByReceipt.getOrDefault(r.getId(), false)) {
                    totals.reconciled = totals.reconciled.plus(r.getAmount());
                }
            } else {
                totals.reversed = totals.reversed.plus(r.getAmount());
            }
            if (r.getCompetenceDate().isBefore(s.getPeriodStart())) {
                priorPeriod++;
            }
            totals.agencyHonorarium = totals.agencyHonorarium.plus(e.getAgencyHonorarium());
            totals.creditorHonorarium = totals.creditorHonorarium.plus(e.getCreditorHonorarium());
            totals.commission = totals.commission.plus(e.getCommissionAmount());
            totals.fixedFee = totals.fixedFee.plus(e.getFixedFee());
            totals.transferEffect = totals.transferEffect.plus(e.getTransferEffect());
        }
        List<ReimbursableExpense> expenses = facts.findOpenExpenses(s.getCreditorId(), end);
        for (ReimbursableExpense e : expenses) {
            totals.expenses = totals.expenses.plus(e.deduction());
        }
        Money adjustments = statements.findAdjustments(s.getId()).stream()
                .filter(a -> a.getStatus() == StatementAdjustment.Status.APPLIED)
                .map(StatementAdjustment::getAmount).reduce(Money.ZERO, Money::plus);
        Money negotiated = negotiated(s);
        Forecast forecast = forecast(s.getCreditorId(), today);
        List<OpenDivergence> divergences = sources.openDivergences(s.getCreditorId());

        Money recovered = totals.receivedAgency.plus(totals.receivedCreditor).plus(totals.reversed);
        Money net = totals.transferEffect.minus(totals.expenses).plus(adjustments);
        Totals result = new Totals(totals.receivedAgency, totals.receivedCreditor, totals.reversed, recovered,
                totals.reconciled, totals.agencyHonorarium, totals.creditorHonorarium, totals.commission, totals.fixedFee,
                totals.expenses, adjustments, net, negotiated, forecast.commission(), pendingReconciliationAmount,
                pendingRulesAmount, divergences.size(), included.size());

        for (Receipt r : included) {
            r.bindTo(s.getId());
            receipts.save(r);
        }
        for (ReimbursableExpense e : expenses) {
            e.bindTo(s.getId());
            facts.save(e);
        }

        Map<String, Object> calculation = new LinkedHashMap<>();
        calculation.put("schema", SettlementStatement.CALCULATION_SCHEMA);
        calculation.put("periodStart", s.getPeriodStart().toString());
        calculation.put("periodEnd", end.toString());
        calculation.put("calculatedAt", now.toString());
        calculation.put("ruleVersions", new ArrayList<>(versions.values()));
        calculation.put("receiptsIncluded", included.size());
        calculation.put("receiptsFromPriorPeriods", priorPeriod);
        calculation.put("expensesIncluded", expenses.size());
        calculation.put("transferEffect", totals.transferEffect.amount());
        calculation.put("formula", "líquido a repassar = Σ efeito no repasse dos lançamentos − despesas reembolsáveis "
                + "+ ajustes");
        calculation.put("pendingReconciliation", pendingReconciliation);
        calculation.put("pendingRules", pendingRules);
        calculation.put("openDivergences", divergences.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id());
            m.put("type", d.type());
            m.put("paymentId", d.paymentId());
            m.put("amount", d.amount());
            m.put("occurredOn", d.occurredOn() == null ? null : d.occurredOn().toString());
            return m;
        }).toList());
        calculation.put("forecast", Map.of("openAgreements", forecast.agreements(),
                "withoutRules", forecast.withoutRules(), "remaining", forecast.remaining().amount()));
        return new Result(result, json.toJson(calculation), included.size());
    }

    /** Libera os itens vinculados (recálculo ou cancelamento de demonstrativo em aberto). */
    void release(SettlementStatement s) {
        for (Receipt r : receipts.findByStatementId(s.getId())) {
            r.unbind();
            receipts.save(r);
        }
        for (ReimbursableExpense e : facts.findExpensesByStatement(s.getId())) {
            e.unbind();
            facts.save(e);
        }
    }

    private CommissionRules rules(UUID ruleSetId) {
        CommissionRuleSet ruleSet = ruleSets.findById(ruleSetId).orElseThrow();
        return codec.read(ruleSet);
    }

    private Money negotiated(SettlementStatement s) {
        Instant from = s.getPeriodStart().atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Instant to = s.getPeriodEnd().plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        return facts.findAgreementsFormalizedBetween(s.getCreditorId(), from, to).stream()
                .map(AgreementFact::getAgreementAmount).reduce(Money.ZERO, Money::plus);
    }

    /** Somatório mutável dos itens incluídos (uso local do cálculo). */
    private static final class Accumulator {
        private Money receivedAgency = Money.ZERO;
        private Money receivedCreditor = Money.ZERO;
        private Money reversed = Money.ZERO;
        private Money reconciled = Money.ZERO;
        private Money agencyHonorarium = Money.ZERO;
        private Money creditorHonorarium = Money.ZERO;
        private Money commission = Money.ZERO;
        private Money fixedFee = Money.ZERO;
        private Money transferEffect = Money.ZERO;
        private Money expenses = Money.ZERO;
    }

    private record Forecast(Money commission, Money remaining, int agreements, int withoutRules) {
    }

    /**
     * Comissão prevista (informativa): saldo ainda não recebido dos acordos em aberto × regras vigentes hoje
     * do contrato da carteira, como recebimento na conta da assessoria, sem honorários.
     */
    private Forecast forecast(UUID creditorId, LocalDate today) {
        Money commission = Money.ZERO;
        Money remainingTotal = Money.ZERO;
        int count = 0;
        int withoutRules = 0;
        Map<UUID, Optional<UUID>> contractByPortfolio = new HashMap<>();
        for (AgreementFact a : facts.findOpenAgreements(creditorId)) {
            Money received = receipts.findByAgreementId(a.getAgreementId()).stream().map(Receipt::getAmount)
                    .reduce(Money.ZERO, Money::plus);
            Money remaining = a.getAgreementAmount().minus(received);
            if (!remaining.isPositive()) {
                continue;
            }
            count++;
            remainingTotal = remainingTotal.plus(remaining);
            Optional<CommissionRules> rules = a.getPortfolioId() == null ? Optional.empty()
                    : contractByPortfolio.computeIfAbsent(a.getPortfolioId(), sources::contractOfPortfolio)
                    .flatMap(contract -> ruleSets.findEffective(contract, today)).map(codec::read);
            if (rules.isEmpty()) {
                withoutRules++;
                continue;
            }
            PaymentType type = a.getInstallmentCount() != null && a.getInstallmentCount() <= 1 ? PaymentType.LUMP_SUM
                    : PaymentType.INSTALLMENTS;
            boolean feeDue = entries.netFixedFee(a.getAgreementId()).isZero();
            CommissionCalculator.Rating rating = CommissionCalculator.rate(rules.get(), new CommissionCalculator.Input(
                    remaining, Money.ZERO, new ReceiptProfile(a.getPortfolioId(), a.getDaysPastDue(), type,
                    ReceivedBy.AGENCY, a.isRenegotiation()), feeDue));
            commission = commission.plus(rating.commission()).plus(rating.fixedFee());
        }
        return new Forecast(commission, remainingTotal, count, withoutRules);
    }

    private static Map<String, Object> item(Receipt r, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("receiptId", r.getId());
        m.put("kind", r.getKind().name());
        m.put("allocationId", r.getAllocationId());
        m.put("agreementId", r.getAgreementId());
        m.put("titleReference", r.getTitleReference());
        m.put("amount", r.getAmount().amount());
        m.put("paidOn", r.getPaidOn().toString());
        m.putAll(extra);
        return m;
    }
}
