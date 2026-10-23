package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.out.SettlementLocks;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources.AgreementPaymentFacts;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources.PaymentFacts;
import br.com.gestorcobranca.settlement.domain.model.Receipt;
import br.com.gestorcobranca.settlement.domain.model.ReceiptKind;
import br.com.gestorcobranca.settlement.domain.model.ReversalNotice;
import br.com.gestorcobranca.settlement.domain.model.rules.PaymentType;
import br.com.gestorcobranca.settlement.domain.model.rules.ReceivedBy;
import br.com.gestorcobranca.settlement.domain.repository.ReceiptRepository;
import br.com.gestorcobranca.settlement.domain.repository.SettlementFactRepository;
import br.com.gestorcobranca.settlement.domain.service.LargestRemainder;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.events.negotiation.AgreementPaymentRecorded;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Registro dos recebimentos a partir dos eventos de pagamento (consumidores, ator de sistema), sob o lock
 * da alocação ({@code paymentId} dos eventos):
 * <ul>
 *   <li>{@code PaymentConfirmed} sem acordo → um recebimento por título avulso (valor, honorários, atraso e
 *       identificação do título no credor vêm do evento);</li>
 *   <li>{@code AgreementPaymentRecorded} aplicado → um recebimento do valor que o negotiation aplicou
 *       (acordo encerrado/não aplicado não é recuperação), com parcelas, títulos e componentes lidos da
 *       distribuição do pagamento no acordo;</li>
 *   <li>{@code PaymentReversed} → compensação proporcional nos recebimentos da alocação; estorno que chega
 *       antes do recebimento fica guardado e é aplicado quando ele chegar.</li>
 * </ul>
 */
@Component
class ReceiptPostingService {

    private static final Logger LOG = LoggerFactory.getLogger(ReceiptPostingService.class);

    private final ReceiptRepository receipts;
    private final SettlementFactRepository facts;
    private final SettlementSources sources;
    private final SettlementLocks locks;
    private final RatingService rating;
    private final SettlementMetrics metrics;
    private final JsonCodec json;

    ReceiptPostingService(ReceiptRepository receipts, SettlementFactRepository facts, SettlementSources sources,
                          SettlementLocks locks, RatingService rating, SettlementMetrics metrics, JsonCodec json) {
        this.receipts = receipts;
        this.facts = facts;
        this.sources = sources;
        this.locks = locks;
        this.rating = rating;
        this.metrics = metrics;
        this.json = json;
    }

    void paymentConfirmed(PaymentConfirmed e) {
        if (e.agreementId() != null) {
            // recuperação de acordo = valor aplicado pelo negotiation (AgreementPaymentRecorded)
            return;
        }
        locks.lockAllocation(e.paymentId());
        Optional<PaymentFacts> payment = sources.paymentOfAllocation(e.paymentId());
        ReceivedBy receivedBy = payment.map(p -> p.receivedByCreditor() ? ReceivedBy.CREDITOR : ReceivedBy.AGENCY)
                .orElse(ReceivedBy.AGENCY);
        LocalDate paidOn = date(e.paidAt() == null ? e.occurredAt() : e.paidAt());
        List<Receipt> created = new ArrayList<>();
        for (PaymentConfirmed.Allocation line : e.allocations()) {
            if (line.titleId() == null || line.amount() == null || line.amount().signum() <= 0) {
                continue;
            }
            boolean exists = receipts.findByAllocationIdAndKind(e.paymentId(), ReceiptKind.PAYMENT).stream()
                    .anyMatch(r -> line.titleId().equals(r.getTitleId()));
            if (exists) {
                metrics.receipt(ReceiptKind.PAYMENT.name(), "duplicate");
                continue;
            }
            Money honorarium = Money.ofNullable(line.honorariumAmount());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("lines", List.of(line(null, null, line.titleId(), line.titleReference(), "TOTAL",
                    Money.of(line.amount()))));
            if (honorarium.isPositive()) {
                detail.put("honorarium", honorarium.amount());
            }
            Receipt receipt = receipts.save(Receipt.payment(new Receipt.PaymentData(e.paymentId(),
                    payment.map(PaymentFacts::paymentId).orElse(null), line.titleId(), line.titleReference(),
                    e.creditorId(), e.portfolioId(), null, null, e.debtorId(), Money.of(line.amount()), honorarium,
                    line.daysPastDue(), PaymentType.LUMP_SUM, receivedBy, false, e.method(), e.source(), paidOn,
                    json.toJson(detail))));
            metrics.receipt(ReceiptKind.PAYMENT.name(), "recorded");
            created.add(receipt);
        }
        if (created.isEmpty()) {
            return;
        }
        created.addAll(applyPendingNotices(e.paymentId()));
        rating.rate(e.creditorId(), created);
    }

    void agreementPaymentRecorded(AgreementPaymentRecorded e) {
        if (!"APPLIED".equals(e.status()) || e.appliedAmount() == null || e.appliedAmount().signum() <= 0) {
            metrics.eventIgnored("agreement_payment_recorded", e.status() == null ? "unknown" : e.status().toLowerCase());
            return;
        }
        locks.lockAllocation(e.paymentId());
        if (!receipts.findByAllocationIdAndKind(e.paymentId(), ReceiptKind.PAYMENT).isEmpty()) {
            metrics.receipt(ReceiptKind.PAYMENT.name(), "duplicate");
            return;
        }
        AgreementPaymentFacts d = sources.agreementPayment(e.agreementId(), e.paymentId())
                .orElseThrow(() -> new IllegalStateException("Distribuição do pagamento " + e.paymentId()
                        + " não encontrada no acordo " + e.agreementId()));
        Optional<PaymentFacts> payment = sources.paymentOfAllocation(e.paymentId());
        ReceivedBy receivedBy = payment.map(p -> p.receivedByCreditor() ? ReceivedBy.CREDITOR : ReceivedBy.AGENCY)
                .orElse(ReceivedBy.AGENCY);
        Money applied = Money.of(e.appliedAmount());
        Money honorarium = d.components().stream().filter(c -> "HONORARIUM".equals(c.component()))
                .map(c -> Money.of(c.amount())).reduce(Money.ZERO, Money::plus).min(applied);
        List<Map<String, Object>> lines = new ArrayList<>();
        d.installments().forEach(i -> lines.add(line(i.installmentId(), i.number(), null, null, "INSTALLMENT",
                Money.of(i.amount()))));
        d.components().forEach(c -> lines.add(line(null, null, c.titleId(), c.titleReference(), c.component(),
                Money.of(c.amount()))));
        List<String> references = d.components().stream().map(SettlementSources.ComponentShare::titleReference)
                .filter(java.util.Objects::nonNull).distinct().toList();
        String titleReference = references.isEmpty() ? null : String.join("|", references);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("lines", lines);
        detail.put("installmentCount", d.installmentCount());
        Receipt receipt = receipts.save(Receipt.payment(new Receipt.PaymentData(e.paymentId(),
                payment.map(PaymentFacts::paymentId).orElse(null), null, titleReference, e.creditorId(), d.portfolioId(),
                null, e.agreementId(), e.debtorId(), applied, honorarium, (int) Math.min(Integer.MAX_VALUE,
                d.maxDaysOverdue()), d.installmentCount() <= 1 ? PaymentType.LUMP_SUM : PaymentType.INSTALLMENTS,
                receivedBy, d.renegotiationNumber() > 0, d.method(), d.source(), date(d.paidAt()), json.toJson(detail))));
        metrics.receipt(ReceiptKind.PAYMENT.name(), "recorded");
        List<Receipt> created = new ArrayList<>(List.of(receipt));
        created.addAll(applyPendingNotices(e.paymentId()));
        rating.rate(e.creditorId(), created);
    }

    void paymentReversed(PaymentReversed e) {
        locks.lockAllocation(e.paymentId());
        if (facts.findNotice(e.reversalId()).isPresent()) {
            metrics.receipt(ReceiptKind.REVERSAL.name(), "duplicate");
            return;
        }
        if (e.amount() == null || e.amount().signum() <= 0) {
            metrics.eventIgnored("payment_reversed", "no_amount");
            return;
        }
        ReversalNotice notice = facts.save(new ReversalNotice(e.reversalId(), e.paymentId(), e.creditorId(),
                e.agreementId(), Money.of(e.amount()), e.reason(), date(e.occurredAt())));
        List<Receipt> created = apply(notice);
        if (created.isEmpty()) {
            LOG.atInfo().addKeyValue("allocationId", e.paymentId()).addKeyValue("reversalId", e.reversalId())
                    .addKeyValue("reason", e.reason())
                    .log("Estorno sem recebimento registrado para a alocação; guardado até o recebimento chegar");
            return;
        }
        rating.rate(e.creditorId(), created);
    }

    private List<Receipt> applyPendingNotices(UUID allocationId) {
        List<Receipt> created = new ArrayList<>();
        for (ReversalNotice notice : facts.findNoticesByAllocation(allocationId)) {
            if (notice.pending().isPositive()) {
                created.addAll(apply(notice));
            }
        }
        return created;
    }

    /** Distribui o saldo do estorno entre os recebimentos da alocação, proporcional ao líquido de cada um. */
    private List<Receipt> apply(ReversalNotice notice) {
        List<Receipt> payments = receipts.findByAllocationIdAndKind(notice.getAllocationId(), ReceiptKind.PAYMENT);
        List<Receipt> withBalance = new ArrayList<>();
        List<Money> nets = new ArrayList<>();
        List<Money> honorariums = new ArrayList<>();
        for (Receipt p : payments) {
            Money net = p.getAmount();
            Money honorarium = p.getHonorariumAmount();
            for (Receipt rev : receipts.findByReversesReceiptId(p.getId())) {
                net = net.plus(rev.getAmount());
                honorarium = honorarium.plus(rev.getHonorariumAmount());
            }
            if (net.isPositive()) {
                withBalance.add(p);
                nets.add(net);
                honorariums.add(honorarium);
            }
        }
        Money available = nets.stream().reduce(Money.ZERO, Money::plus);
        Money toApply = notice.pending().min(available);
        if (!toApply.isPositive()) {
            return List.of();
        }
        List<Money> shares = LargestRemainder.split(toApply, nets);
        List<Receipt> created = new ArrayList<>();
        for (int i = 0; i < withBalance.size(); i++) {
            Money share = shares.get(i);
            if (!share.isPositive()) {
                continue;
            }
            Money honorarium = share.equals(nets.get(i)) ? honorariums.get(i)
                    : Money.of(honorariums.get(i).amount().multiply(share.amount())
                    .divide(nets.get(i).amount(), 12, RoundingMode.HALF_EVEN));
            Receipt reversal = receipts.save(withBalance.get(i).reversal(share, honorarium, notice.getReversalId(),
                    notice.getReason(), notice.getReversedOn()));
            metrics.receipt(ReceiptKind.REVERSAL.name(), "recorded");
            created.add(reversal);
        }
        notice.apply(toApply);
        facts.save(notice);
        return created;
    }

    private static Map<String, Object> line(UUID installmentId, Integer installmentNumber, UUID titleId,
                                            String titleReference, String component, Money amount) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("installmentId", installmentId);
        line.put("installmentNumber", installmentNumber);
        line.put("titleId", titleId);
        line.put("titleReference", titleReference);
        line.put("component", component);
        line.put("amount", amount.amount());
        return line;
    }

    private static LocalDate date(Instant at) {
        return at.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
    }
}
