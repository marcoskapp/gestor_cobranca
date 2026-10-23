package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.port.out.PixProvider.PixTransaction;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.Refund;
import br.com.gestorcobranca.payment.domain.model.DivergenceSource;
import br.com.gestorcobranca.payment.domain.model.DivergenceType;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentInstruction;
import br.com.gestorcobranca.payment.domain.model.PaymentMethod;
import br.com.gestorcobranca.payment.domain.model.PaymentStatus;
import br.com.gestorcobranca.payment.domain.model.PixAccount;
import br.com.gestorcobranca.payment.domain.model.ReconciliationMode;
import br.com.gestorcobranca.payment.domain.model.ReversalReason;
import br.com.gestorcobranca.payment.domain.model.ReversalSource;
import br.com.gestorcobranca.payment.domain.model.ReviewMode;
import br.com.gestorcobranca.payment.domain.repository.PaymentInstructionRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentReversalRepository;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Money;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Aplica um Pix confirmado pelo PSP: idempotente pelo {@code endToEndId} (índice único). Sem pagamento
 * para a transação:
 * <ul>
 *   <li>sem instrução para o {@code txid} → divergência {@code UNMATCHED} (Pix sem identificação);</li>
 *   <li>pagamento da mesma instrução já criado pelo arquivo de retorno sem identificador, com o mesmo
 *       valor → vincula a transação (não duplica a baixa);</li>
 *   <li>demais → pagamento integrado confirmado e conciliado pela API do PSP, alocado ao destino da
 *       instrução; segunda transação para a mesma instrução → divergência {@code DUPLICATE_PAYMENT};
 *       valor diferente do da instrução → {@code AMOUNT_MISMATCH}.</li>
 * </ul>
 * Devoluções ({@code DEVOLVIDO}): total → estorno compensatório; parcial → divergência
 * {@code PARTIAL_REFUND} para análise (o estorno é sempre total no sistema).
 */
@Component
class PixSettlement {

    private final PaymentRepository payments;
    private final PaymentInstructionRepository instructions;
    private final PaymentReversalRepository reversals;
    private final PaymentConfirmation confirmation;
    private final PaymentReversals paymentReversals;
    private final PaymentRecorder recorder;
    private final Divergences divergences;
    private final PaymentMetrics metrics;
    private final Clock clock;

    PixSettlement(PaymentRepository payments, PaymentInstructionRepository instructions,
                  PaymentReversalRepository reversals, PaymentConfirmation confirmation,
                  PaymentReversals paymentReversals, PaymentRecorder recorder, Divergences divergences,
                  PaymentMetrics metrics, Clock clock) {
        this.payments = payments;
        this.instructions = instructions;
        this.reversals = reversals;
        this.confirmation = confirmation;
        this.paymentReversals = paymentReversals;
        this.recorder = recorder;
        this.divergences = divergences;
        this.metrics = metrics;
        this.clock = clock;
    }

    void apply(PixAccount account, PixTransaction tx) {
        Money amount = Money.of(tx.amount());
        Optional<Payment> existing = payments.findByProviderTransactionId(tx.endToEndId());
        if (existing.isPresent()) {
            Payment p = payments.findByIdForUpdate(existing.get().getId()).orElseThrow();
            metrics.pixSync(applyRefunds(p, tx) ? "refund" : "unchanged");
            return;
        }
        Optional<PaymentInstruction> found = tx.txid() == null ? Optional.empty() : instructions.findByTxid(tx.txid());
        if (found.isEmpty()) {
            metrics.pixSync("unidentified");
            divergences.open(DivergenceType.UNMATCHED, DivergenceSource.WEBHOOK, account.getCreditorId(), null, null, null,
                    tx.endToEndId(), amount, null, day(tx.paidAt()));
            return;
        }
        PaymentInstruction instruction = instructions.findByIdForUpdate(found.get().getId()).orElseThrow();
        List<Payment> previous = payments.findByInstructionIdOrderByCreatedAtAsc(instruction.getId());
        Optional<Payment> fromStatement = previous.stream().filter(p -> p.getProviderTransactionId() == null
                && p.isEffective() && p.getAmount().equals(amount)).findFirst();
        Instant now = Instant.now(clock);
        if (fromStatement.isPresent()) {
            Payment p = payments.findByIdForUpdate(fromStatement.get().getId()).orElseThrow();
            p.attachProviderTransaction(tx.endToEndId());
            payments.save(p);
            metrics.pixSync("linked");
            applyRefunds(p, tx);
            return;
        }
        boolean duplicate = previous.stream().anyMatch(p -> p.getStatus() != PaymentStatus.REJECTED);
        Payment payment = payments.saveAndFlush(Payment.fromProvider(PaymentMethod.PIX, instruction.getCreditorId(),
                instruction.getDebtorId(), amount, tx.paidAt() == null ? now : tx.paidAt(), instruction.getId(),
                tx.endToEndId(), ReviewMode.PROVIDER, now));
        confirmation.confirmed(payment, instruction, "provider");
        if (payment.reconcile(ReconciliationMode.PROVIDER_API, null, null, now)) {
            payments.save(payment);
            recorder.reconciled(payment, ReconciliationMode.PROVIDER_API, null);
        }
        if (duplicate) {
            divergences.open(DivergenceType.DUPLICATE_PAYMENT, DivergenceSource.WEBHOOK, payment.getCreditorId(),
                    payment.getId(), instruction.getId(), null, tx.endToEndId(), amount, instruction.getAmount(),
                    day(payment.getPaidAt()));
        } else if (!amount.equals(instruction.getAmount())) {
            divergences.open(DivergenceType.AMOUNT_MISMATCH, DivergenceSource.WEBHOOK, payment.getCreditorId(),
                    payment.getId(), instruction.getId(), null, tx.endToEndId(), amount, instruction.getAmount(),
                    day(payment.getPaidAt()));
        }
        metrics.pixSync(duplicate ? "duplicate_payment" : "confirmed");
        applyRefunds(payment, tx);
    }

    /** @return verdadeiro se alguma devolução nova foi tratada */
    private boolean applyRefunds(Payment payment, PixTransaction tx) {
        List<Refund> done = tx.refunds().stream().filter(r -> "DEVOLVIDO".equals(r.status()) && r.amount() != null).toList();
        if (done.isEmpty() || payment.getStatus() == PaymentStatus.REVERSED) {
            return false;
        }
        Money refunded = done.stream().map(r -> Money.of(r.amount())).reduce(Money.ZERO, Money::plus);
        Refund last = done.getLast();
        String reference = reference(tx.endToEndId(), last);
        if (!refunded.isLessThan(payment.getAmount())) {
            if (reversals.existsByProviderReference(reference) || !payment.isEffective()) {
                return false;
            }
            paymentReversals.reverse(payment, ReversalReason.PROVIDER_REFUND, ReversalSource.PROVIDER, reference, null);
            return true;
        }
        boolean opened = false;
        for (Refund refund : done) {
            opened |= divergences.open(DivergenceType.PARTIAL_REFUND, DivergenceSource.WEBHOOK, payment.getCreditorId(),
                    payment.getId(), payment.getInstructionId(), null, reference(tx.endToEndId(), refund),
                    Money.of(refund.amount()), payment.getAmount(), day(refund.at() == null ? Instant.now(clock)
                            : refund.at())).isPresent();
        }
        return opened;
    }

    /** Identificador global da devolução ({@code rtrId}) ou, sem ele, um resumo de e2e + id da devolução. */
    static String reference(String endToEndId, Refund refund) {
        if (refund.returnId() != null && refund.returnId().matches("[A-Za-z0-9]{32}")) {
            return refund.returnId();
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((endToEndId + "|" + refund.id()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LocalDate day(Instant at) {
        return at.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
    }
}
