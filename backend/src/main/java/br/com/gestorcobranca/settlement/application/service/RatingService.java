package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.out.SettlementLocks;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources.ContractFacts;
import br.com.gestorcobranca.settlement.domain.model.CommissionEntry;
import br.com.gestorcobranca.settlement.domain.model.CommissionRuleSet;
import br.com.gestorcobranca.settlement.domain.model.RatingStatus;
import br.com.gestorcobranca.settlement.domain.model.Receipt;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules;
import br.com.gestorcobranca.settlement.domain.repository.CommissionEntryRepository;
import br.com.gestorcobranca.settlement.domain.repository.CommissionRuleSetRepository;
import br.com.gestorcobranca.settlement.domain.repository.ReceiptRepository;
import br.com.gestorcobranca.settlement.domain.service.CommissionCalculator;
import br.com.gestorcobranca.settlement.domain.service.CommissionCalculator.Rating;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Apuração dos recebimentos no razão de comissão (RB08): resolve o contrato (carteira do recebimento ou
 * contrato único vigente do credor), a versão de regras vigente na data do pagamento e lança a comissão
 * com a memória de cálculo. Sem contrato ou regra, o recebimento fica {@code PENDING_RULES} e é apurado
 * quando a regra for publicada. Estornos são compensados com a mesma versão do lançamento original.
 * Executa sob o lock do credor (e do acordo, para o valor fixo por acordo).
 */
@Component
class RatingService {

    private static final Logger LOG = LoggerFactory.getLogger(RatingService.class);

    private final ReceiptRepository receipts;
    private final CommissionEntryRepository entries;
    private final CommissionRuleSetRepository ruleSets;
    private final SettlementSources sources;
    private final SettlementLocks locks;
    private final RulesCodec codec;
    private final SettlementViews views;
    private final BusinessEventLogger businessEvents;
    private final SettlementMetrics metrics;
    private final Clock clock;

    RatingService(ReceiptRepository receipts, CommissionEntryRepository entries, CommissionRuleSetRepository ruleSets,
                  SettlementSources sources, SettlementLocks locks, RulesCodec codec, SettlementViews views,
                  BusinessEventLogger businessEvents, SettlementMetrics metrics, Clock clock) {
        this.receipts = receipts;
        this.entries = entries;
        this.ruleSets = ruleSets;
        this.sources = sources;
        this.locks = locks;
        this.codec = codec;
        this.views = views;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Apura os recebimentos informados (do mesmo credor), na ordem: confirmados antes das compensações.
     *
     * @param lookups fatos de outros módulos (pré-carregados pelo consumidor de evento)
     */
    void rate(UUID creditorId, List<Receipt> pending, SettlementSources lookups) {
        if (pending.isEmpty()) {
            return;
        }
        locks.lockCreditor(creditorId);
        pending.stream().sorted(Comparator.comparing((Receipt r) -> r.isPayment() ? 0 : 1)
                        .thenComparing(Receipt::getCompetenceDate))
                .forEach(r -> rateOne(r, lookups));
    }

    /** Tenta apurar todos os pendentes do credor; devolve quantos foram apurados. */
    int ratePending(UUID creditorId) {
        locks.lockCreditor(creditorId);
        List<Receipt> pending = receipts.findByCreditorIdAndRatingStatus(creditorId, RatingStatus.PENDING_RULES).stream()
                .sorted(Comparator.comparing((Receipt r) -> r.isPayment() ? 0 : 1)
                        .thenComparing(Receipt::getCompetenceDate))
                .toList();
        int rated = 0;
        for (Receipt r : pending) {
            if (r.getRatingStatus() == RatingStatus.PENDING_RULES && rateOne(r, sources)) {
                rated++;
            }
        }
        return rated;
    }

    private boolean rateOne(Receipt r, SettlementSources lookups) {
        if (r.getRatingStatus() == RatingStatus.RATED) {
            return true;
        }
        return r.isPayment() ? ratePayment(r, lookups) : rateReversal(r);
    }

    private boolean ratePayment(Receipt r, SettlementSources lookups) {
        UUID contract = resolveContract(r, lookups);
        if (contract == null) {
            return pending(r, "NO_CONTRACT");
        }
        r.resolveContract(contract);
        Optional<CommissionRuleSet> found = ruleSets.findEffective(contract, r.getPaidOn());
        if (found.isEmpty()) {
            return pending(r, "NO_RULES");
        }
        CommissionRuleSet ruleSet = found.get();
        CommissionRules rules = codec.read(ruleSet);
        boolean feeDue = false;
        if (r.getAgreementId() != null && rules.fixedFeePerAgreement() != null
                && rules.fixedFeePerAgreement().signum() > 0) {
            locks.lockAgreement(r.getAgreementId());
            feeDue = entries.netFixedFee(r.getAgreementId()).isZero();
        }
        Rating rating = CommissionCalculator.rate(rules, new CommissionCalculator.Input(r.getAmount(),
                r.getHonorariumAmount(), r.profile(), feeDue));
        CommissionEntry entry = entries.save(CommissionEntry.accrual(r, ruleSet, rating, views.memoryJson(rating)));
        r.markRated(Instant.now(clock));
        receipts.save(r);
        metrics.rating(r.getKind().name(), "rated");
        businessEvents.log(base(BusinessEventTypes.COMMISSION_ACCRUED, r, entry).amount(r.getAmount())
                .attr("commission", rating.commission().amount()).attr("fixedFee", rating.fixedFee().amount())
                .attr("agencyHonorarium", rating.agencyHonorarium().amount())
                .attr("creditorHonorarium", rating.creditorHonorarium().amount())
                .attr("rate", rating.rate().value()).attr("ruleCode", rating.ruleCode())
                .attr("ruleVersion", ruleSet.getRuleVersion()).attr("paymentType", r.getPaymentType())
                .attr("daysPastDue", r.getDaysPastDue()).attr("receivedBy", r.getReceivedBy())
                .attr("renegotiation", r.isRenegotiation()).attr("method", r.getMethod())
                .attr("paidOn", r.getPaidOn().toString()));
        receipts.findByReversesReceiptId(r.getId()).stream()
                .filter(rev -> rev.getRatingStatus() == RatingStatus.PENDING_RULES)
                .forEach(this::rateReversal);
        return true;
    }

    private boolean rateReversal(Receipt reversal) {
        Optional<CommissionEntry> original = entries.findByReceiptId(reversal.getReversesReceiptId());
        if (original.isEmpty()) {
            return pending(reversal, "ORIGINAL_PENDING");
        }
        CommissionEntry accrual = original.get();
        Rating position = accrual.asRating();
        for (CommissionEntry previous : entries.findByReversesEntryId(accrual.getId())) {
            position = position.plus(previous.asRating());
        }
        Money reversed = reversal.getAmount().negate();
        if (reversed.isGreaterThan(position.recovered())) {
            LOG.atError().addKeyValue("receiptId", reversal.getId()).addKeyValue("entryId", accrual.getId())
                    .log("Compensação maior que o saldo do lançamento de comissão; recebimento de estorno inconsistente");
            throw new IllegalStateException("Estorno acima do saldo do lançamento " + accrual.getId());
        }
        Rating rating = CommissionCalculator.reverse(position, reversed, reversal.getReceivedBy());
        CommissionEntry entry = entries.save(CommissionEntry.reversal(reversal, accrual, rating, views.memoryJson(rating)));
        reversal.resolveContract(receipts.findById(reversal.getReversesReceiptId()).map(Receipt::getContractId)
                .orElse(null));
        reversal.markRated(Instant.now(clock));
        receipts.save(reversal);
        metrics.rating(reversal.getKind().name(), "rated");
        businessEvents.log(base(BusinessEventTypes.COMMISSION_REVERSED, reversal, entry).amount(reversal.getAmount())
                .attr("commission", rating.commission().amount()).attr("fixedFee", rating.fixedFee().amount())
                .attr("agencyHonorarium", rating.agencyHonorarium().amount()).attr("reason", reversal.getReversalReason())
                .attr("reversesEntryId", accrual.getId()).attr("ruleVersion", accrual.getRuleVersion())
                .attr("finalReversal", rating.recovered().negate().equals(position.recovered())));
        return true;
    }

    private BusinessEvent base(String type, Receipt r, CommissionEntry entry) {
        return BusinessEvent.of(type).creditor(r.getCreditorId()).portfolio(r.getPortfolioId()).debtor(r.getDebtorId())
                .reference("settlement_receipt", r.getId()).attr("entryId", entry.getId())
                .attr("allocationId", r.getAllocationId()).attr("agreementId", r.getAgreementId())
                .attr("contractId", r.getContractId());
    }

    private boolean pending(Receipt r, String reason) {
        boolean changed = !reason.equals(r.getPendingReason());
        r.markPending(reason);
        receipts.save(r);
        if (changed) {
            metrics.rating(r.getKind().name(), "pending_" + reason.toLowerCase());
            if (r.isPayment()) {
                LOG.atWarn().addKeyValue("receiptId", r.getId()).addKeyValue("creditorId", r.getCreditorId())
                        .addKeyValue("reason", reason)
                        .log("Recebimento sem regra de comissão vigente; aguardando publicação das regras do contrato");
                businessEvents.log(BusinessEvent.of(BusinessEventTypes.SETTLEMENT_RECEIPT_PENDING_RULES)
                        .creditor(r.getCreditorId()).portfolio(r.getPortfolioId()).reference("settlement_receipt", r.getId())
                        .amount(r.getAmount()).attr("reason", reason).attr("paidOn", r.getPaidOn().toString())
                        .attr("contractId", r.getContractId()));
            }
        }
        return false;
    }

    /** Contrato da carteira do recebimento ou, sem carteira, o único contrato do credor vigente na data. */
    private UUID resolveContract(Receipt r, SettlementSources lookups) {
        if (r.getContractId() != null) {
            return r.getContractId();
        }
        if (r.getPortfolioId() != null) {
            Optional<UUID> fromPortfolio = lookups.contractOfPortfolio(r.getPortfolioId());
            if (fromPortfolio.isPresent()) {
                return fromPortfolio.get();
            }
        }
        List<ContractFacts> effective = lookups.contracts(r.getCreditorId()).stream()
                .filter(c -> c.effectiveOn(r.getPaidOn())).toList();
        return effective.size() == 1 ? effective.getFirst().id() : null;
    }
}
