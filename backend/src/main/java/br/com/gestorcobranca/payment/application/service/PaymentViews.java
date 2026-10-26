package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.InstructionView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.AllocationView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.LineView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.RequestedTargetView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReversalView;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.DivergenceView;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.StatementImportView;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.StatementLineView;
import br.com.gestorcobranca.payment.domain.model.Divergence;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentAllocation;
import br.com.gestorcobranca.payment.domain.model.PaymentInstruction;
import br.com.gestorcobranca.payment.domain.model.PaymentReversal;
import br.com.gestorcobranca.payment.domain.model.StatementImport;
import br.com.gestorcobranca.payment.domain.model.BankStatementLine;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.SensitiveDataMasker;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Conversão de entidades em views (nunca expõem entidades nem segredos). */
final class PaymentViews {

    private static final TypeReference<List<UUID>> TARGET_ITEMS = new TypeReference<>() {
    };

    private PaymentViews() {
    }

    /**
     * @param targetItems ids do destino solicitado ({@code target_items}) já decodificados; ignorados sem
     *                    {@code target_kind}
     */
    static PaymentView payment(Payment p, List<PaymentAllocation> allocations, Optional<PaymentReversal> reversal,
                               List<UUID> targetItems) {
        Money pendingReallocation = p.isEffective()
                ? allocations.stream().map(PaymentAllocation::availableForReallocation).reduce(p.unallocated(), Money::plus)
                : Money.ZERO;
        return new PaymentView(p.getId(), PaymentEnums.name(p.getSource()), PaymentEnums.name(p.getMethod()),
                PaymentEnums.name(p.getStatus()), p.getCreditorId(), p.getDebtorId(), p.getAmount().amount(), p.getPaidAt(),
                p.getInstructionId(), p.getProviderTransactionId(), p.getExternalReference(),
                PaymentEnums.name(p.getExternalOrigin()), p.getNote(), p.getInformedBy(), p.getInformedAt(),
                p.isReviewRequired(), PaymentEnums.name(p.getReviewMode()), p.getReviewedBy(), p.getReviewedAt(),
                p.getReviewNote(), p.getConfirmedAt(), p.getReconciledAt(), PaymentEnums.name(p.getReconciliationMode()),
                p.getReversedAt(), p.getAllocatedAmount().amount(), p.unallocated().amount(), pendingReallocation.amount(),
                p.getEvidenceKey() != null, p.getEvidenceSha256(), allocations.stream().map(PaymentViews::allocation).toList(),
                reversal.map(PaymentViews::reversal).orElse(null), requestedTarget(p, targetItems), null,
                p.getVersion() == null ? 0 : p.getVersion());
    }

    /** Ids do destino solicitado ({@code target_items}, JSON gravado no registro do pagamento externo). */
    static List<UUID> targetItems(Payment p, JsonCodec json) {
        if (p.getTargetItems() == null || p.getTargetItems().isBlank()) {
            return List.of();
        }
        return json.fromJson(p.getTargetItems(), TARGET_ITEMS);
    }

    static RequestedTargetView requestedTarget(Payment p, List<UUID> targetItems) {
        if (p.getTargetKind() == null) {
            return null;
        }
        return new RequestedTargetView(p.getTargetKind().name(), p.getTargetAgreementId(), targetItems,
                PaymentEnums.name(p.getTargetStrategy()));
    }

    static AllocationView allocation(PaymentAllocation a) {
        return new AllocationView(a.getId(), a.getKind().name(), a.getOrigin().name(), a.getStatus().name(),
                a.getAgreementId(), a.getPortfolioId(), a.getAmount().amount(), a.getReleasedAmount().amount(),
                a.getOutcome().name(), amount(a.getAppliedAmount()), amount(a.getUnappliedAmount()),
                a.availableForReallocation().amount(), a.getSourceAllocationId(), a.getRefundReference(),
                a.getLines().stream().map(l -> new LineView(l.installmentId(), l.titleId(), l.amount().amount())).toList(),
                a.getCreatedAt(), a.getReversedAt());
    }

    static ReversalView reversal(PaymentReversal r) {
        return new ReversalView(r.getId(), r.getAmount().amount(), r.getReason().name(), r.getSource().name(), r.getNote(),
                r.getReversedAt(), r.getCreatedBy());
    }

    static InstructionView instruction(PaymentInstruction i) {
        return new InstructionView(i.getId(), i.getType().name(), i.getStatus().name(), i.getCreditorId(),
                i.getPortfolioId(), i.getDebtorId(), i.getCaseId(), i.getAgreementId(), i.getInstallmentId(),
                i.getInstallmentNumber(), i.getTitleId(), i.getAmount().amount(), i.getDueDate(), i.getExpiresAt(),
                i.getTxid(), i.getPixCopyPaste(), i.getLocation(), i.getFailureCode(), i.getPaymentId(), i.getPaidAt(),
                i.getCancelledAt(), i.getCancelReason(), i.getChannel(), i.getCreatedAt(),
                i.getVersion() == null ? 0 : i.getVersion());
    }

    static StatementImportView statementImport(StatementImport s) {
        return new StatementImportView(s.getId(), s.getFormat().name(), s.getStatus().name(), s.getCreditorId(),
                s.getFileName(), s.getFileSize(), s.getFileSha256(), s.getRequestedBy(), s.getCreatedAt(), s.getStartedAt(),
                s.getFinishedAt(), s.getTotalLines(), s.getReconciledLines(), s.getCreatedPayments(), s.getDivergentLines(),
                s.getIgnoredLines(), s.getFailureCode());
    }

    static StatementLineView line(BankStatementLine l) {
        return new StatementLineView(l.getId(), l.getLineNumber(), l.getPaidOn(), l.getAmount().amount(),
                l.isCredit() ? "C" : "D", l.getTransactionId(), l.getReference(), masked(l.getDescription()),
                l.getStatus().name(), l.getPaymentId());
    }

    static DivergenceView divergence(Divergence d, Optional<BankStatementLine> line) {
        return new DivergenceView(d.getId(), d.getType().name(), d.getSource().name(), d.getStatus().name(),
                d.getCreditorId(), d.getPaymentId(), d.getInstructionId(), d.getStatementLineId(), d.getProviderReference(),
                d.getAmount().amount(), amount(d.getExpectedAmount()), d.getOccurredOn(),
                line.map(BankStatementLine::getDescription).map(PaymentViews::masked).orElse(null),
                PaymentEnums.name(d.getResolution()), d.getResolutionNote(), d.getResolvedPaymentId(), d.getResolvedBy(),
                d.getResolvedAt(), d.getCreatedAt());
    }

    private static String masked(String text) {
        return text == null ? null : SensitiveDataMasker.mask(text);
    }

    private static BigDecimal amount(Money money) {
        return money == null ? null : money.amount();
    }
}
