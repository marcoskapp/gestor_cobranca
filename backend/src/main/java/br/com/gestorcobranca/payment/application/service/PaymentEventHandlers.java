package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.domain.model.PaymentAllocation;
import br.com.gestorcobranca.payment.domain.model.PaymentInstruction;
import br.com.gestorcobranca.payment.domain.model.TargetKind;
import br.com.gestorcobranca.payment.domain.repository.PaymentAllocationRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentInstructionRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.events.debt.TitleStatusChanged;
import br.com.gestorcobranca.shared.events.negotiation.AgreementPaymentRecorded;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.negotiation.InstallmentStatusChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Efeitos dos eventos consumidos (executados no tenant do evento, como ator de sistema). */
@Component
class PaymentEventHandlers {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentEventHandlers.class);
    private static final Set<String> CLOSED_AGREEMENT = Set.of("SETTLED", "BROKEN", "CANCELLED", "RENEGOTIATED");
    private static final Set<String> CLOSED_INSTALLMENT = Set.of("PAID", "CANCELLED");
    private static final Set<String> CLOSED_TITLE = Set.of("SETTLED", "CANCELLED", "RETURNED");

    private final PaymentAllocationRepository allocations;
    private final PaymentInstructionRepository instructions;
    private final InstructionService instructionService;
    private final AuditTrail auditTrail;
    private final PaymentMetrics metrics;
    private final Clock clock;

    PaymentEventHandlers(PaymentAllocationRepository allocations, PaymentInstructionRepository instructions,
                         InstructionService instructionService, AuditTrail auditTrail, PaymentMetrics metrics,
                         Clock clock) {
        this.allocations = allocations;
        this.instructions = instructions;
        this.instructionService = instructionService;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.clock = clock;
    }

    void agreementPaymentRecorded(AgreementPaymentRecorded e) {
        metrics.eventConsumed("agreement_payment_recorded");
        PaymentAllocation allocation = allocations.findById(e.paymentId())
                .filter(a -> a.getKind() == TargetKind.AGREEMENT).orElse(null);
        if (allocation == null) {
            return;
        }
        Money unapplied = Money.ofNullable(e.unappliedAmount());
        if (!allocation.recordOutcome(Money.ofNullable(e.appliedAmount()), unapplied, Instant.now(clock))) {
            return;
        }
        allocations.save(allocation);
        metrics.agreementOutcome(allocation.getOutcome().name());
        if (unapplied.isPositive()) {
            metrics.leftover("APPLIED".equals(e.status()) ? "excess" : "agreement_closed");
            LOG.atWarn().addKeyValue("allocationId", allocation.getId()).addKeyValue("paymentId", allocation.getPaymentId())
                    .addKeyValue("agreementId", e.agreementId()).addKeyValue("status", e.status())
                    .log("Valor não aplicado pelo negotiation; pagamento entra na fila de realocação");
            auditTrail.record(AuditEntry.of("payment.unapplied", AllocationService.RESOURCE, allocation.getPaymentId())
                    .creditor(e.creditorId()).created("allocationId", allocation.getId())
                    .created("unappliedAmount", unapplied).meta("agreementStatus", e.agreementStatus())
                    .meta("negotiationStatus", e.status()));
        }
    }

    void agreementStatusChanged(AgreementStatusChanged e) {
        if (CLOSED_AGREEMENT.contains(e.newStatus())) {
            cancel(instructions.findByAgreementIdAndStatusIn(e.agreementId(), InstructionService.OPEN),
                    "Acordo encerrado (" + e.newStatus() + ")", "agreement_" + e.newStatus().toLowerCase());
        }
    }

    void installmentStatusChanged(InstallmentStatusChanged e) {
        if (CLOSED_INSTALLMENT.contains(e.newStatus())) {
            cancel(instructions.findByInstallmentIdAndStatusIn(e.installmentId(), InstructionService.OPEN),
                    "Parcela " + (e.newStatus().equals("PAID") ? "paga" : "cancelada"),
                    "installment_" + e.newStatus().toLowerCase());
        }
    }

    void titleStatusChanged(TitleStatusChanged e) {
        if (CLOSED_TITLE.contains(e.newStatus())) {
            cancel(instructions.findByTitleIdAndStatusIn(e.titleId(), InstructionService.OPEN),
                    "Título encerrado (" + e.newStatus() + ")", "title_" + e.newStatus().toLowerCase());
        }
    }

    private void cancel(List<PaymentInstruction> open, String reason, String trigger) {
        for (PaymentInstruction found : open) {
            PaymentInstruction instruction = instructions.findByIdForUpdate(found.getId()).orElseThrow();
            if (instruction.getStatus().isOpen()) {
                instructionService.cancel(instruction, reason, trigger);
            }
        }
    }
}
