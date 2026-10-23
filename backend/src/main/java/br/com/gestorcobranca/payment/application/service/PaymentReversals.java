package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentReversal;
import br.com.gestorcobranca.payment.domain.model.PaymentStatus;
import br.com.gestorcobranca.payment.domain.model.ReversalReason;
import br.com.gestorcobranca.payment.domain.model.ReversalSource;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentReversalRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Estorno total de um pagamento (evento compensatório): registro append-only, pagamento
 * {@code REVERSED}, alocações revertidas (títulos avulsos com estorno espelho no razão) e um
 * {@code PaymentReversed} por alocação publicada. Chamado com o pagamento bloqueado.
 */
@Component
class PaymentReversals {

    private final PaymentRepository payments;
    private final PaymentReversalRepository reversals;
    private final AllocationService allocationService;
    private final PaymentRecorder recorder;
    private final Clock clock;

    PaymentReversals(PaymentRepository payments, PaymentReversalRepository reversals, AllocationService allocationService,
                     PaymentRecorder recorder, Clock clock) {
        this.payments = payments;
        this.reversals = reversals;
        this.allocationService = allocationService;
        this.recorder = recorder;
        this.clock = clock;
    }

    PaymentReversal reverse(Payment payment, ReversalReason reason, ReversalSource source, String providerReference,
                            String note) {
        Instant now = Instant.now(clock);
        PaymentStatus previous = payment.reverse(now);
        PaymentReversal reversal = reversals.save(new PaymentReversal(payment.getId(), payment.getAmount(), reason, source,
                providerReference, note, previous, now));
        allocationService.reverseAll(payment, reason.name());
        payments.save(payment);
        recorder.reversed(payment, reversal);
        return reversal;
    }
}
