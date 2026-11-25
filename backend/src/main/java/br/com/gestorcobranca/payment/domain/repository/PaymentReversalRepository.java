package br.com.gestorcobranca.payment.domain.repository;

import br.com.gestorcobranca.payment.domain.model.PaymentReversal;

import java.util.Optional;
import java.util.UUID;

public interface PaymentReversalRepository {

    PaymentReversal save(PaymentReversal reversal);

    Optional<PaymentReversal> findByPaymentId(UUID paymentId);

    /** Estornos dos pagamentos (anonimização do titular). */
    java.util.List<PaymentReversal> findByPaymentIdIn(java.util.Collection<UUID> paymentIds);

    boolean existsByProviderReference(String providerReference);
}
