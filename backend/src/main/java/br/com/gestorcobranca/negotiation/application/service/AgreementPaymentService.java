package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement.BucketPosting;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement.Receipt;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementPayment;
import br.com.gestorcobranca.negotiation.domain.repository.AgreementRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Baixa de parcelas por pagamentos confirmados e estornos ({@code shared.events.payment}). Executa
 * no consumidor (ator de sistema), com a linha do acordo bloqueada; os lançamentos no razão do
 * título são feitos pelo job {@link LedgerPostingJob}.
 */
@Component
class AgreementPaymentService {

    private static final Logger LOG = LoggerFactory.getLogger(AgreementPaymentService.class);

    private final AgreementRepository agreements;
    private final AgreementRecorder recorder;
    private final JobQueue jobs;
    private final AuditTrail auditTrail;
    private final NegotiationMetrics metrics;
    private final JsonCodec json;
    private final Clock clock;

    AgreementPaymentService(AgreementRepository agreements, AgreementRecorder recorder, JobQueue jobs,
                            AuditTrail auditTrail, NegotiationMetrics metrics, JsonCodec json, Clock clock) {
        this.agreements = agreements;
        this.recorder = recorder;
        this.jobs = jobs;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
    }

    void paymentConfirmed(PaymentConfirmed e) {
        if (e.agreementId() == null) {
            return;
        }
        Optional<Agreement> found = agreements.findByIdForUpdate(e.agreementId());
        if (found.isEmpty()) {
            metrics.paymentConsumed("IGNORED");
            LOG.atWarn().addKeyValue("agreementId", e.agreementId()).addKeyValue("paymentId", e.paymentId())
                    .log("Pagamento confirmado para acordo inexistente no tenant; ignorado");
            return;
        }
        Agreement a = found.get();
        if (agreements.findPayment(a.getId(), e.paymentId()).isPresent()) {
            metrics.paymentConsumed("DUPLICATE");
            return;
        }
        Map<UUID, Money> byInstallment = new LinkedHashMap<>();
        Money allocated = Money.ZERO;
        for (PaymentConfirmed.Allocation allocation : e.allocations()) {
            if (allocation.agreementInstallmentId() != null && allocation.amount() != null
                    && allocation.amount().signum() > 0) {
                byInstallment.merge(allocation.agreementInstallmentId(), Money.of(allocation.amount()), Money::plus);
                allocated = allocated.plus(Money.of(allocation.amount()));
            }
        }
        Money amount = Money.ofNullable(e.amount());
        Money unassigned = amount.minus(allocated).max(Money.ZERO);
        Instant now = Instant.now(clock);
        Instant paidAt = e.paidAt() == null ? e.occurredAt() : e.paidAt();
        if (!a.getStatus().isActive()) {
            agreements.save(new AgreementPayment(a.getId(), e.paymentId(), amount, Money.ZERO, amount, paidAt, e.source(),
                    e.method(), "{}", "[]"));
            metrics.paymentConsumed("UNAPPLIED");
            LOG.atWarn().addKeyValue("agreementId", a.getId()).addKeyValue("paymentId", e.paymentId())
                    .addKeyValue("status", a.getStatus())
                    .log("Pagamento para acordo encerrado não aplicado; exige realocação no módulo payment");
            return;
        }
        Receipt receipt = a.receive(byInstallment, unassigned, today(), paidAt);
        List<BucketPosting> postings = receipt.appliedTotal().isPositive() ? a.allocate(receipt.appliedTotal()) : List.of();
        agreements.save(new AgreementPayment(a.getId(), e.paymentId(), amount, receipt.appliedTotal(), receipt.unapplied(),
                paidAt, e.source(), e.method(), LedgerLines.allocationsJson(json, receipt.applied()),
                LedgerLines.postingsJson(json, postings)));
        agreements.save(a);
        receipt.installmentChanges().forEach(c -> recorder.installmentChanged(a, c, now));
        receipt.statusChanges().forEach(c -> recorder.statusChanged(a, c, now));
        if (receipt.appliedTotal().isPositive()) {
            LedgerPostingJob.enqueue(jobs, a.getId());
        }
        auditTrail.record(AuditEntry.of("agreement.payment_applied", AgreementService.RESOURCE, a.getId())
                .creditor(a.getCreditorId()).created("paymentId", e.paymentId())
                .created("appliedAmount", receipt.appliedTotal().amount())
                .created("unappliedAmount", receipt.unapplied().amount()).meta("status", a.getStatus()));
        if (receipt.unapplied().isPositive()) {
            LOG.atWarn().addKeyValue("agreementId", a.getId()).addKeyValue("paymentId", e.paymentId())
                    .log("Pagamento acima do saldo do acordo; excedente não aplicado");
        }
        metrics.paymentConsumed(receipt.unapplied().isPositive() ? "PARTIAL" : "APPLIED");
    }

    void paymentReversed(PaymentReversed e) {
        List<AgreementPayment> rows = e.agreementId() != null
                ? agreements.findPayment(e.agreementId(), e.paymentId()).stream().toList()
                : agreements.findPaymentsByPaymentId(e.paymentId());
        if (rows.isEmpty()) {
            metrics.reversalConsumed("UNKNOWN");
            return;
        }
        Instant now = Instant.now(clock);
        for (AgreementPayment row : rows) {
            if (row.getStatus() != AgreementPayment.Status.APPLIED) {
                metrics.reversalConsumed(row.getStatus() == AgreementPayment.Status.REVERSED ? "DUPLICATE" : "UNAPPLIED");
                continue;
            }
            Agreement a = agreements.findByIdForUpdate(row.getAgreementId()).orElseThrow();
            AgreementPayment.LedgerStatus before = row.reverse(e.reversalId(), e.reason(), now);
            Receipt receipt = a.revert(LedgerLines.allocations(json, row.getInstallmentAllocations()), today(), now);
            a.deallocate(LedgerLines.postings(json, row.getLedgerPostings()), before == AgreementPayment.LedgerStatus.POSTED);
            agreements.save(row);
            agreements.save(a);
            receipt.installmentChanges().forEach(c -> recorder.installmentChanged(a, c, now));
            receipt.statusChanges().forEach(c -> recorder.statusChanged(a, c, now));
            if (before == AgreementPayment.LedgerStatus.POSTED) {
                LedgerPostingJob.enqueue(jobs, a.getId());
            }
            auditTrail.record(AuditEntry.of("agreement.payment_reversed", AgreementService.RESOURCE, a.getId())
                    .creditor(a.getCreditorId()).created("paymentId", e.paymentId()).created("reversalId", e.reversalId())
                    .created("amount", row.getAppliedAmount().amount()).meta("status", a.getStatus()));
            metrics.reversalConsumed("REVERSED");
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }
}
