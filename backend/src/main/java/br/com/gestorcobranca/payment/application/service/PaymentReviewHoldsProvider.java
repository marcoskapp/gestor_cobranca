package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.negotiation.application.port.spi.PaymentReviewHolds;
import br.com.gestorcobranca.payment.domain.model.PaymentAllocation;
import br.com.gestorcobranca.payment.domain.model.PaymentStatus;
import br.com.gestorcobranca.payment.domain.repository.PaymentAllocationRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Implementação, pelo payment, da porta que o negotiation declara para suspender a quebra enquanto há
 * pagamento em revisão (ADR-0010): pendentes ({@code INFORMED}) com o acordo como destino, alocações
 * confirmadas ainda sem retorno do negotiation (em trânsito) e revisões concluídas. Consulta de sistema,
 * sempre dentro da transação de quem chama (job de quebra ou revisão, com o acordo bloqueado), sob a RLS
 * do tenant corrente — por isso sem transação própria.
 */
@Component
class PaymentReviewHoldsProvider implements PaymentReviewHolds {

    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;

    PaymentReviewHoldsProvider(PaymentRepository payments, PaymentAllocationRepository allocations) {
        this.payments = payments;
        this.allocations = allocations;
    }

    @Override
    public PaymentHold holdFor(UUID agreementId, Instant reviewedSince) {
        List<PendingReview> pending = payments.findPendingReviewByAgreement(agreementId).stream()
                .map(p -> new PendingReview(p.getId(), p.getInformedAt(), p.getAmount().amount())).toList();
        List<UUID> inTransit = allocations.findInFlightByAgreement(agreementId).stream().map(PaymentAllocation::getId)
                .toList();
        List<ReviewOutcome> reviewed = reviewedSince == null ? List.of()
                : payments.findReviewedByAgreementSince(agreementId, reviewedSince).stream()
                .map(p -> new ReviewOutcome(p.getId(), p.getStatus() != PaymentStatus.REJECTED, p.getReviewedAt()))
                .toList();
        return new PaymentHold(pending, inTransit, reviewed);
    }
}
