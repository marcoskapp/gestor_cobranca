package br.com.gestorcobranca.payment.domain.repository;

import br.com.gestorcobranca.payment.domain.model.PaymentReversal;

import java.util.Optional;
import java.util.UUID;

public interface PaymentReversalRepository {

    PaymentReversal save(PaymentReversal reversal);

    Optional<PaymentReversal> findByPaymentId(UUID paymentId);

    boolean existsByProviderReference(String providerReference);
}
