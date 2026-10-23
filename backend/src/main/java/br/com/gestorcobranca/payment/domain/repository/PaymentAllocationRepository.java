package br.com.gestorcobranca.payment.domain.repository;

import br.com.gestorcobranca.payment.domain.model.PaymentAllocation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAllocationRepository {

    PaymentAllocation save(PaymentAllocation allocation);

    Optional<PaymentAllocation> findById(UUID id);

    List<PaymentAllocation> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    List<PaymentAllocation> findByPaymentIdIn(Collection<UUID> paymentIds);

    /** Alocações a acordo ainda sem retorno do negotiation (em trânsito). */
    List<PaymentAllocation> findInFlightByAgreement(UUID agreementId);

    /** Alocações em vigor com linha na parcela. */
    List<PaymentAllocation> findActiveByInstallment(UUID installmentId);
}
