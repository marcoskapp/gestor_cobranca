package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries.EntryView;
import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries.ReceiptView;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases.CalculationView;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases.MemoryLineView;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases.RuleSetView;
import br.com.gestorcobranca.settlement.application.port.in.StatementExportUseCases.ExportView;
import br.com.gestorcobranca.settlement.application.port.in.StatementUseCases.AdjustmentView;
import br.com.gestorcobranca.settlement.application.port.in.StatementUseCases.ExpenseView;
import br.com.gestorcobranca.settlement.application.port.in.StatementUseCases.StatementView;
import br.com.gestorcobranca.settlement.application.port.in.StatementUseCases.TotalsView;
import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases.TransferView;
import br.com.gestorcobranca.settlement.domain.model.CommissionEntry;
import br.com.gestorcobranca.settlement.domain.model.CommissionRuleSet;
import br.com.gestorcobranca.settlement.domain.model.CreditorTransfer;
import br.com.gestorcobranca.settlement.domain.model.Receipt;
import br.com.gestorcobranca.settlement.domain.model.ReimbursableExpense;
import br.com.gestorcobranca.settlement.domain.model.SettlementStatement;
import br.com.gestorcobranca.settlement.domain.model.SettlementStatement.Totals;
import br.com.gestorcobranca.settlement.domain.model.StatementAdjustment;
import br.com.gestorcobranca.settlement.domain.model.StatementExport;
import br.com.gestorcobranca.settlement.domain.service.CommissionCalculator.MemoryLine;
import br.com.gestorcobranca.settlement.domain.service.CommissionCalculator.Rating;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;

/** Conversão de entidades em views dos casos de uso (nunca expõem entidades). */
@Component
class SettlementViews {

    private static final TypeReference<List<MemoryLine>> MEMORY = new TypeReference<>() {
    };

    private final RulesCodec rules;
    private final JsonCodec json;

    SettlementViews(RulesCodec rules, JsonCodec json) {
        this.rules = rules;
        this.json = json;
    }

    RuleSetView ruleSet(CommissionRuleSet r) {
        return new RuleSetView(r.getId(), r.getCreditorId(), r.getContractId(), r.getRuleVersion(), r.getStatus().name(),
                r.getEffectiveFrom(), r.getEffectiveTo(), rules.read(r), r.getNotes(), r.getRulesEditedBy(),
                r.getRulesEditedAt(), r.getPublishedBy(), r.getPublishedAt(), r.getVersion() == null ? 0 : r.getVersion());
    }

    static CalculationView calculation(Rating r) {
        return new CalculationView(r.ruleCode(), r.rate().value(), r.recovered().amount(), r.agencyHonorarium().amount(),
                r.creditorHonorarium().amount(), r.commissionBase().amount(), r.commission().amount(),
                r.fixedFee().amount(), r.transferEffect().amount(),
                r.memory().stream().map(m -> new MemoryLineView(m.step(), m.description(), m.base(), m.rate(), m.amount()))
                        .toList());
    }

    String memoryJson(Rating rating) {
        return json.toJson(rating.memory());
    }

    EntryView entry(CommissionEntry e) {
        List<MemoryLine> memory = json.fromJson(e.getCalculation(), MEMORY);
        Rating r = e.asRating();
        Rating withMemory = new Rating(r.ruleCode(), r.rate(), r.recovered(), r.agencyHonorarium(), r.creditorHonorarium(),
                r.commissionBase(), r.commission(), r.fixedFee(), r.transferEffect(), memory);
        return new EntryView(e.getId(), e.getKind().name(), e.getRuleSetId(), e.getRuleVersion(), e.getReversesEntryId(),
                calculation(withMemory));
    }

    ReceiptView receipt(Receipt r, CommissionEntry entry) {
        return new ReceiptView(r.getId(), r.getKind().name(), r.getAllocationId(), r.getPaymentId(), r.getCreditorId(),
                r.getPortfolioId(), r.getContractId(), r.getAgreementId(), r.getTitleId(), r.getTitleReference(),
                r.getAmount().amount(), r.getHonorariumAmount().amount(), r.getDaysPastDue(), r.getPaymentType().name(),
                r.getReceivedBy().name(), r.isRenegotiation(), r.getMethod(), r.getPaidOn(), r.getCompetenceDate(),
                r.getRatingStatus().name(), r.getPendingReason(), r.getReversesReceiptId(), r.getReversalReason(),
                r.getStatementId(), entry == null ? null : entry(entry));
    }

    Map<String, Object> detail(Receipt r) {
        return json.toMap(r.getDetail());
    }

    StatementView statement(SettlementStatement s) {
        Totals t = s.totals();
        TotalsView totals = new TotalsView(t.receivedAgency().amount(), t.receivedCreditor().amount(),
                t.reversed().amount(), t.recovered().amount(), t.reconciled().amount(), t.agencyHonorarium().amount(),
                t.creditorHonorarium().amount(), t.commission().amount(), t.fixedFee().amount(), t.expenses().amount(),
                t.adjustments().amount(), t.netTransfer().amount(), s.getTransferredAmount().amount(),
                s.getStatus().isClosed() ? s.outstanding().amount() : java.math.BigDecimal.ZERO.setScale(2),
                t.negotiated().amount(), t.forecastCommission().amount(), t.pendingReconciliation().amount(),
                t.pendingRules().amount(), t.openDivergences(), t.receipts());
        return new StatementView(s.getId(), s.getCreditorId(), s.getSequenceNumber(), s.getPeriodStart(),
                s.getPeriodEnd(), s.getStatus().name(), totals,
                s.getCalculation() == null ? Map.of() : json.toMap(s.getCalculation()), s.getCalculatedAt(),
                s.getCalculatedBy(), s.getReviewedAt(), s.getReviewedBy(), s.getApprovedAt(), s.getApprovedBy(),
                s.getSentAt(), s.getSentBy(), s.getSentChannel(), s.getSentReference(), s.getCreditorResponseAt(),
                s.getCreditorResponseBy(), s.getCreditorResponseNote(), s.getCancelledReason(),
                s.getVersion() == null ? 0 : s.getVersion());
    }

    static AdjustmentView adjustment(StatementAdjustment a) {
        return new AdjustmentView(a.getId(), a.getStatementId(), a.getCategory().name(), a.getAmount().amount(),
                a.getReason(), a.getRelatedStatementId(), a.getStatus().name(), a.getRequestedBy(), a.getRequestedAt(),
                a.getDecidedBy(), a.getDecidedAt(), a.getDecisionNote());
    }

    static ExpenseView expense(ReimbursableExpense e) {
        return new ExpenseView(e.getId(), e.getKind().name(), e.getLegalExpenseId(), e.getMatterId(), e.getExpenseType(),
                e.getAmount().amount(), e.getIncurredOn(), e.getAdvancedBy(), e.isReimbursable(), e.isHasReceipt(),
                e.getEffect().name(), e.getCompetenceDate(), e.getStatus().name(), e.getStatementId());
    }

    static ExportView export(StatementExport e) {
        return new ExportView(e.getId(), e.getStatementId(), e.getFormat().name(), e.getFileName(), e.getFileSize(),
                e.getSha256(), e.getRowsCount(), e.getGeneratedBy(), e.getCreatedAt());
    }

    static TransferView transfer(CreditorTransfer t) {
        return new TransferView(t.getId(), t.getStatementId(), t.getCreditorId(), t.getDirection().name(),
                t.getAmount().amount(), t.getTransferredOn(), t.getBankReference(), t.getNote(), t.getEvidenceName(),
                t.getEvidenceSha256(), t.getRegisteredBy(), t.getCreatedAt(), t.getStatus().name(), t.getCancelledAt(),
                t.getCancelledBy(), t.getCancelReason());
    }
}
