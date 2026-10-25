package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.domain.model.Divergence;
import br.com.gestorcobranca.payment.domain.model.InstructionStatus;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentAllocation;
import br.com.gestorcobranca.payment.domain.model.PaymentInstruction;
import br.com.gestorcobranca.payment.domain.model.PaymentReversal;
import br.com.gestorcobranca.payment.domain.model.ReconciliationMode;
import br.com.gestorcobranca.payment.domain.model.TargetKind;
import br.com.gestorcobranca.payment.domain.repository.PaymentAllocationRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.events.payment.PaymentInstructionStatusChanged;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Fatos de negócio, auditoria e métricas das mudanças de estado de pagamentos, instruções e
 * divergências (mesma transação da mudança). Eventos de negócio só com ids, enums e valores —
 * nunca documento, nome, observação ou descrição de extrato.
 */
@Component
class PaymentRecorder {

    private final BusinessEventLogger businessEvents;
    private final AuditTrail auditTrail;
    private final PaymentAllocationRepository allocations;
    private final PaymentMetrics metrics;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PaymentRecorder(BusinessEventLogger businessEvents, AuditTrail auditTrail, PaymentAllocationRepository allocations,
                    PaymentMetrics metrics, ApplicationEventPublisher events, Clock clock) {
        this.events = events;
        this.businessEvents = businessEvents;
        this.auditTrail = auditTrail;
        this.allocations = allocations;
        this.metrics = metrics;
        this.clock = clock;
    }

    void instructionCreated(PaymentInstruction i) {
        metrics.instruction(i.getType().name(), "created");
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PAYMENT_INSTRUCTION_CREATED).creditor(i.getCreditorId())
                .portfolio(i.getPortfolioId()).debtor(i.getDebtorId()).collectionCase(i.getCaseId())
                .reference("payment_instruction", i.getId()).channel(i.getChannel()).amount(i.getAmount())
                .attr("type", i.getType()).attr("targetKind", i.targetKind())
                .attr("agreementId", i.getAgreementId() == null ? null : i.getAgreementId().toString())
                .attr("installmentNumber", i.getInstallmentNumber())
                .attr("dueDate", i.getDueDate() == null ? null : i.getDueDate().toString())
                .attr("expiresInMinutes", Duration.between(Instant.now(clock), i.getExpiresAt()).toMinutes()));
        auditTrail.record(AuditEntry.of("payment_instruction.created", "payment_instruction", i.getId())
                .creditor(i.getCreditorId()).created("type", i.getType()).created("amount", i.getAmount())
                .created("targetKind", i.targetKind()).created("expiresAt", i.getExpiresAt())
                .meta("agreementId", i.getAgreementId()).meta("installmentId", i.getInstallmentId())
                .meta("titleId", i.getTitleId()).meta("txid", i.getTxid()));
    }

    /**
     * Publica {@code PaymentInstructionStatusChanged} (na transação da mudança; só ids, enums e valores).
     *
     * @param previous situação antes da mudança
     */
    void instructionStatusChanged(PaymentInstruction i, InstructionStatus previous) {
        if (previous == i.getStatus()) {
            return;
        }
        events.publishEvent(new PaymentInstructionStatusChanged(Ids.newId(), i.getTenantId(), Instant.now(clock),
                i.getId(), i.getType().name(), previous == null ? null : previous.name(), i.getStatus().name(),
                i.getCreditorId(), i.getDebtorId(), i.getAgreementId(), i.getInstallmentId(), i.getAmount().amount(),
                i.getPaidAt()));
    }

    /** @param reviewReason motivo da exigência de revisão (AMOUNT, DESTINATION_CUMULATIVE, INFORMER_CUMULATIVE) ou nulo */
    void informed(Payment p, String reviewReason) {
        metrics.payment(p.getSource().name(), p.getMethod().name(), "INFORMED");
        businessEvents.log(base(BusinessEventTypes.PAYMENT_INFORMED, p, List.of()).amount(p.getAmount())
                .attr("origin", p.getExternalOrigin()).attr("reviewRequired", p.isReviewRequired())
                .attr("reviewReason", reviewReason)
                .attr("targetKind", p.getTargetKind()).attr("hasEvidence", p.getEvidenceSha256() != null)
                .attr("informedBy", p.getInformedBy() == null ? null : p.getInformedBy().toString())
                .attr("daysSincePayment", Duration.between(p.getPaidAt(), Instant.now(clock)).toDays()));
        auditTrail.record(AuditEntry.of("payment.informed", AllocationService.RESOURCE, p.getId())
                .creditor(p.getCreditorId()).created("method", p.getMethod()).created("amount", p.getAmount())
                .created("paidAt", p.getPaidAt()).created("origin", p.getExternalOrigin())
                .created("reviewRequired", p.isReviewRequired()).created("evidenceSha256", p.getEvidenceSha256())
                .meta("externalReference", p.getExternalReference()).meta("targetKind", p.getTargetKind())
                .meta("reviewReason", reviewReason));
    }

    /**
     * @param trigger origem da confirmação: provider, statement, auto_low_risk, review
     */
    void confirmed(Payment p, List<PaymentAllocation> created, Money leftover, String leftoverReason, String trigger,
                   PaymentInstruction instruction) {
        Instant now = Instant.now(clock);
        metrics.payment(p.getSource().name(), p.getMethod().name(), "CONFIRMED");
        metrics.confirmationLatency(p.getSource().name(), Duration.between(p.getPaidAt(), now));
        PaymentAllocation first = created.isEmpty() ? null : created.getFirst();
        BusinessEvent event = base(BusinessEventTypes.PAYMENT_CONFIRMED, p, created).amount(p.getAmount())
                .attr("trigger", trigger).attr("reviewMode", p.getReviewMode())
                .attr("allocations", created.size()).attr("allocatedAmount", p.getAllocatedAmount().amount())
                .attr("unallocatedAmount", p.getAmount().minus(p.getAllocatedAmount()).amount())
                .attr("leftoverReason", leftover != null && leftover.isPositive() ? leftoverReason : null)
                .attr("targetKind", first == null ? null : first.getKind())
                .attr("agreementId", first == null || first.getAgreementId() == null ? null
                        : first.getAgreementId().toString())
                .attr("instructionType", instruction == null ? null : instruction.getType())
                .attr("delayMinutes", Duration.between(p.getPaidAt(), now).toMinutes())
                .durationMs(Math.max(0, Duration.between(p.getPaidAt(), now).toMillis()));
        if (instruction != null) {
            event.channel(instruction.getChannel());
        }
        businessEvents.log(event);
        auditTrail.record(AuditEntry.of("payment.confirmed", AllocationService.RESOURCE, p.getId())
                .creditor(p.getCreditorId()).change("status", p.getInformedAt() == null ? null : "INFORMED", "CONFIRMED")
                .meta("trigger", trigger).meta("reviewMode", p.getReviewMode())
                .meta("allocatedAmount", p.getAllocatedAmount()).meta("instructionId", p.getInstructionId())
                .meta("providerTransactionId", p.getProviderTransactionId()));
    }

    void rejected(Payment p) {
        metrics.payment(p.getSource().name(), p.getMethod().name(), "REJECTED");
        businessEvents.log(base(BusinessEventTypes.PAYMENT_REJECTED, p, List.of()).amount(p.getAmount())
                .attr("origin", p.getExternalOrigin())
                .attr("reviewedBy", p.getReviewedBy() == null ? null : p.getReviewedBy().toString())
                .durationMs(Math.max(0, Duration.between(p.getInformedAt(), p.getReviewedAt()).toMillis())));
        auditTrail.record(AuditEntry.of("payment.rejected", AllocationService.RESOURCE, p.getId())
                .creditor(p.getCreditorId()).change("status", "INFORMED", "REJECTED"));
    }

    void reconciled(Payment p, ReconciliationMode mode, UUID statementImportId) {
        metrics.payment(p.getSource().name(), p.getMethod().name(), "RECONCILED");
        businessEvents.log(base(BusinessEventTypes.PAYMENT_RECONCILED, p, allocations.findByPaymentIdOrderByCreatedAtAsc(
                p.getId())).amount(p.getAmount()).attr("mode", mode)
                .attr("statementImportId", statementImportId == null ? null : statementImportId.toString())
                .attr("daysFromPayment", Duration.between(p.getPaidAt(), Instant.now(clock)).toDays()));
        auditTrail.record(AuditEntry.of("payment.reconciled", AllocationService.RESOURCE, p.getId())
                .creditor(p.getCreditorId()).change("status", "CONFIRMED", "RECONCILED").meta("mode", mode)
                .meta("statementLineId", p.getStatementLineId()));
    }

    void reversed(Payment p, PaymentReversal r) {
        metrics.payment(p.getSource().name(), p.getMethod().name(), "REVERSED");
        metrics.reversal(r.getReason().name(), r.getSource().name());
        List<PaymentAllocation> all = allocations.findByPaymentIdOrderByCreatedAtAsc(p.getId());
        businessEvents.log(base(BusinessEventTypes.PAYMENT_REVERSED, p, all).amount(r.getAmount())
                .attr("reason", r.getReason()).attr("reversalSource", r.getSource()).attr("previousStatus", r.getPreviousStatus())
                .attr("allocations", all.size())
                .attr("daysSinceConfirmation", p.getConfirmedAt() == null ? null
                        : Duration.between(p.getConfirmedAt(), r.getReversedAt()).toDays()));
        auditTrail.record(AuditEntry.of("payment.reversed", AllocationService.RESOURCE, p.getId())
                .creditor(p.getCreditorId()).change("status", r.getPreviousStatus(), "REVERSED")
                .created("reversalId", r.getId()).created("reason", r.getReason()).created("source", r.getSource())
                .created("amount", r.getAmount()).meta("providerReference", r.getProviderReference()));
    }

    void reallocated(Payment p, PaymentAllocation source, PaymentAllocation created, Money amount) {
        businessEvents.log(base(BusinessEventTypes.PAYMENT_REALLOCATED, p, List.of(created)).amount(amount)
                .attr("fromKind", source == null ? "UNALLOCATED" : source.getKind().name())
                .attr("fromAgreementId", source == null || source.getAgreementId() == null ? null
                        : source.getAgreementId().toString())
                .attr("toKind", created.getKind())
                .attr("toAgreementId", created.getAgreementId() == null ? null : created.getAgreementId().toString()));
        auditTrail.record(AuditEntry.of("payment.reallocated", AllocationService.RESOURCE, p.getId())
                .creditor(p.getCreditorId()).created("allocationId", created.getId()).created("amount", amount)
                .created("toKind", created.getKind()).meta("sourceAllocationId", source == null ? null : source.getId())
                .meta("refundReference", created.getRefundReference()));
    }

    void divergenceOpened(Divergence d) {
        metrics.divergence(d.getType().name(), "OPEN");
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PAYMENT_DIVERGENCE).creditor(d.getCreditorId())
                .reference("payment_divergence", d.getId()).amount(d.getAmount())
                .attr("type", d.getType()).attr("source", d.getSource()).attr("status", d.getStatus())
                .attr("paymentId", d.getPaymentId() == null ? null : d.getPaymentId().toString())
                .attr("expectedAmount", d.getExpectedAmount() == null ? null : d.getExpectedAmount().amount()));
        auditTrail.record(AuditEntry.of("payment_divergence.opened", "payment_divergence", d.getId())
                .creditor(d.getCreditorId()).created("type", d.getType()).created("source", d.getSource())
                .created("amount", d.getAmount()).meta("paymentId", d.getPaymentId())
                .meta("statementLineId", d.getStatementLineId()).meta("providerReference", d.getProviderReference()));
    }

    void divergenceResolved(Divergence d) {
        metrics.divergence(d.getType().name(), d.getStatus().name());
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PAYMENT_DIVERGENCE).creditor(d.getCreditorId())
                .reference("payment_divergence", d.getId()).amount(d.getAmount())
                .attr("type", d.getType()).attr("source", d.getSource()).attr("status", d.getStatus())
                .attr("resolution", d.getResolution())
                .attr("resolvedBy", d.getResolvedBy() == null ? null : d.getResolvedBy().toString())
                .durationMs(Math.max(0, Duration.between(d.getCreatedAt(), d.getResolvedAt()).toMillis())));
        auditTrail.record(AuditEntry.of("payment_divergence.resolved", "payment_divergence", d.getId())
                .creditor(d.getCreditorId()).change("status", "OPEN", d.getStatus())
                .created("resolution", d.getResolution()).meta("resolvedPaymentId", d.getResolvedPaymentId()));
    }

    private static BusinessEvent base(String type, Payment p, List<PaymentAllocation> allocated) {
        UUID portfolio = allocated.stream().map(PaymentAllocation::getPortfolioId).filter(Objects::nonNull).findFirst()
                .orElse(null);
        UUID caseId = allocated.stream().filter(a -> a.getKind() == TargetKind.AGREEMENT).map(PaymentAllocation::getCaseId)
                .filter(Objects::nonNull).findFirst().orElse(null);
        return BusinessEvent.of(type).creditor(p.getCreditorId()).portfolio(portfolio).debtor(p.getDebtorId())
                .collectionCase(caseId).reference(AllocationService.RESOURCE, p.getId())
                .attr("source", p.getSource()).attr("method", p.getMethod());
    }
}
