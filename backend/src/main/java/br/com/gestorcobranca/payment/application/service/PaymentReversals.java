package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.port.out.ExternalPaymentLocks;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentReversal;
import br.com.gestorcobranca.payment.domain.model.PaymentStatus;
import br.com.gestorcobranca.payment.domain.model.ReversalReason;
import br.com.gestorcobranca.payment.domain.model.ReversalSource;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentReversalRepository;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Estorno total de um pagamento (evento compensatório): registro append-only, pagamento
 * {@code REVERSED}, alocações revertidas (títulos avulsos com estorno espelho no razão) e um
 * {@code PaymentReversed} por alocação publicada. Chamado com o pagamento bloqueado.
 * <p>
 * Pagamento recebido pelo credor (H07 parte 3): toma antes a trava do devedor no credor (a mesma do registro externo,
 * RS-118) e cria a retenção da cobrança na mesma transação, antes de publicar os eventos — erro na retenção desfaz o
 * estorno inteiro (RS-108).
 */
@Component
class PaymentReversals {

    private final PaymentRepository payments;
    private final PaymentReversalRepository reversals;
    private final AllocationService allocationService;
    private final PaymentRecorder recorder;
    private final Clock clock;
    private final ExternalPaymentLocks locks;
    private final CurrentActor currentActor;

    PaymentReversals(PaymentRepository payments, PaymentReversalRepository reversals, AllocationService allocationService,
                     PaymentRecorder recorder, Clock clock, ExternalPaymentLocks locks, CurrentActor currentActor) {
        this.locks = locks;
        this.currentActor = currentActor;
        this.payments = payments;
        this.reversals = reversals;
        this.allocationService = allocationService;
        this.recorder = recorder;
        this.clock = clock;
    }

    PaymentReversal reverse(Payment payment, ReversalReason reason, ReversalSource source, String providerReference,
                            String note) {
        if (payment.receivedByCreditor()) {
            locks.lockCreditorDebtor(currentActor.tenantId(), payment.getCreditorId(), payment.getDebtorId());
        }
        Instant now = Instant.now(clock);
        PaymentStatus previous = payment.reverse(now);
        PaymentReversal reversal = reversals.save(new PaymentReversal(payment.getId(), payment.getAmount(), reason, source,
                providerReference, note, previous, now));
        allocationService.reverseAll(payment, reason.name(), reversal);
        payments.save(payment);
        recorder.reversed(payment, reversal);
        return reversal;
    }
}
